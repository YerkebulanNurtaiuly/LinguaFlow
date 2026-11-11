package com.linguaflow.vocabulary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.linguaflow.ai.InvalidLlmOutputException;
import com.linguaflow.ai.StructuredLlm;
import com.linguaflow.ai.StructuredResponse;
import com.linguaflow.ai.cache.AiCache;
import com.linguaflow.ai.cache.AiCacheKey;
import com.linguaflow.ai.job.AiJob;
import com.linguaflow.ai.job.AiJobHandler;
import com.linguaflow.ai.job.AiJobType;
import com.linguaflow.ai.prompt.PromptLibrary;
import com.linguaflow.ai.prompt.PromptTemplate;
import com.linguaflow.ai.prompt.PromptVar;
import com.linguaflow.ai.prompt.PromptVariables;
import com.linguaflow.ai.prompt.RenderedPrompt;
import com.linguaflow.common.error.ApiException;
import com.linguaflow.user.UserLanguageService;
import com.linguaflow.vocabulary.EnrichmentAnswer.Batch;
import com.linguaflow.vocabulary.EnrichmentAnswer.Word;
import com.linguaflow.vocabulary.EnrichmentRules.Input;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Enriches a batch of words with AI (VOC-05): translation, part of speech, gender and
 * plural, verb forms, CEFR level, examples and notes.
 * <ol>
 * <li>Only words still {@code PENDING_AI} are handled, so a retry or a second run skips
 * finished ones and deleted words simply drop out.</li>
 * <li>Each word is looked up in the shared AI cache (AI-06) by language, explanation
 * language, normalized lemma and the learner's part of speech. The prompt carries
 * nothing learner-specific, so its answers may be shared.</li>
 * <li>The misses go to the model in one batch call. If its answer is still invalid after
 * the retry with feedback, each word is asked on its own, so one odd word doesn't fail
 * the rest.</li>
 * <li>Every answer passes {@link EnrichmentRules#check} and is merged by
 * {@link EnrichmentRules#merge}: AI only fills what the learner left empty. The word
 * becomes READY; a word with no usable answer becomes AI_FAILED.</li>
 * </ol>
 * Provider errors, the daily quota and an open circuit propagate, so the queue retries or
 * postpones the job (AI-04, AI-05).
 */
@Component
class VocabularyEnrichmentHandler implements AiJobHandler {

	private static final Logger log = LoggerFactory.getLogger(VocabularyEnrichmentHandler.class);

	/**
	 * The cache namespace: both templates' versions, so changing either starts a fresh
	 * cache. They share the vocabulary rules and the answer record.
	 */
	static final String CACHE_VERSION = PromptTemplate.VOCABULARY_ENRICHMENT_BATCH.versionTag() + "+"
			+ PromptTemplate.VOCABULARY_ENRICHMENT.versionTag();

	private static final int MERGE_ATTEMPTS = 3;

	private final VocabularyItemRepository items;

	private final StructuredLlm llm;

	private final PromptLibrary prompts;

	private final AiCache cache;

	private final UserLanguageService userLanguages;

	private final TransactionTemplate tx;

	VocabularyEnrichmentHandler(VocabularyItemRepository items, StructuredLlm llm, PromptLibrary prompts,
			AiCache cache, UserLanguageService userLanguages, PlatformTransactionManager transactions) {
		this.items = items;
		this.llm = llm;
		this.prompts = prompts;
		this.cache = cache;
		this.userLanguages = userLanguages;
		this.tx = new TransactionTemplate(transactions);
	}

	/** A word waiting for AI, read once at the start of the job. */
	record Pending(UUID id, String languageCode, String normalizedLemma, Input input) {
	}

	@Override
	public AiJobType type() {
		return AiJobType.VOCABULARY_ENRICHMENT;
	}

	@Override
	public Map<String, Object> handle(AiJob job) {
		UUID userId = Objects.requireNonNull(job.getUserId(), "enrichment jobs belong to a user");
		List<Pending> pending = Objects.requireNonNull(tx.execute(status -> items
			.findAllForEnrichmentByIdIn(itemIds(job))
			.stream()
			.filter(item -> item.getAiStatus() == AiStatus.PENDING_AI && item.getUserId().equals(userId))
			.map(item -> new Pending(item.getId(), item.getLanguageCode(), item.getNormalizedLemma(),
					new Input(item.getLemma(), item.getPartOfSpeech())))
			.toList()));
		int ready = 0;
		int failed = 0;
		int fromCache = 0;
		Map<String, List<Pending>> byLanguage = new LinkedHashMap<>();
		pending.forEach(word -> byLanguage.computeIfAbsent(word.languageCode(), k -> new ArrayList<>()).add(word));
		for (Map.Entry<String, List<Pending>> group : byLanguage.entrySet()) {
			String language = group.getKey();
			String explanation;
			try {
				explanation = userLanguages.explanationLanguageFor(userId, language);
			}
			catch (ApiException ex) {
				// The learner dropped the language meanwhile; its words are gone too
				continue;
			}
			Map<Pending, Word> answers = new LinkedHashMap<>();
			List<Pending> misses = new ArrayList<>();
			for (Pending word : group.getValue()) {
				var cached = cache.get(cacheKey(word, explanation), Word.class)
					.filter(answer -> llm.problems(answer, (value, problems) -> EnrichmentRules.check("", word.input(),
							language, value, problems)).isEmpty());
				if (cached.isPresent()) {
					answers.put(word, cached.get());
					fromCache++;
				}
				else {
					misses.add(word);
				}
			}
			if (!misses.isEmpty()) {
				answers.putAll(ask(job, language, explanation, misses));
			}
			for (Pending word : group.getValue()) {
				Word answer = answers.get(word);
				if (answer != null && merge(word, answer)) {
					ready++;
				}
				else if (answer == null) {
					markFailed(List.of(word.id()));
					failed++;
				}
			}
		}
		log.info("Enriched {} words for job {} ({} from the cache, {} failed)", ready, job.getId(), fromCache, failed);
		return Map.of("ready", ready, "failed", failed, "fromCache", fromCache);
	}

	/** Words whose enrichment failed for good are marked, so the learner sees it. */
	@Override
	public void onGiveUp(AiJob job) {
		markFailed(itemIds(job));
	}

	/** The model's answers for the words, by word; words without a usable answer are left out. */
	private Map<Pending, Word> ask(AiJob job, String language, String explanation, List<Pending> words) {
		List<Input> inputs = words.stream().map(Pending::input).toList();
		RenderedPrompt batch = prompts.render(PromptTemplate.VOCABULARY_ENRICHMENT_BATCH,
				PromptVariables.forLanguages(language, explanation)
					.set(PromptVar.WORDS, inputs.stream().map(Input::line).toList()));
		Map<Pending, Word> answers = new LinkedHashMap<>();
		try {
			StructuredResponse<Batch> response = llm.complete(job.caller(), batch, Batch.class,
					(value, problems) -> EnrichmentRules.checkBatch(inputs, language, value, problems));
			for (int i = 0; i < words.size(); i++) {
				answers.put(words.get(i), response.value().words().get(i));
			}
			store(batch, words, answers, explanation);
			return answers;
		}
		catch (InvalidLlmOutputException ex) {
			if (words.size() == 1) {
				log.warn("Enrichment of one word failed in job {}: {}", job.getId(), ex.problems());
				return answers;
			}
			log.warn("Batch enrichment of {} words failed in job {}; asking word by word", words.size(), job.getId());
		}
		for (Pending word : words) {
			RenderedPrompt single = prompts.render(PromptTemplate.VOCABULARY_ENRICHMENT,
					PromptVariables.forLanguages(language, explanation)
						.set(PromptVar.WORD, word.input().lemma())
						.set(PromptVar.PART_OF_SPEECH, word.input().partOfSpeech()));
			try {
				Word answer = llm.complete(job.caller(), single, Word.class,
						(value, problems) -> EnrichmentRules.check("", word.input(), language, value, problems))
					.value();
				answers.put(word, answer);
				store(single, List.of(word), Map.of(word, answer), explanation);
			}
			catch (InvalidLlmOutputException ex) {
				log.warn("Enrichment of a word failed in job {}: {}", job.getId(), ex.problems());
			}
		}
		return answers;
	}

	/** Shares good answers through the cache, unless the input looked like an injection attempt. */
	private void store(RenderedPrompt prompt, List<Pending> words, Map<Pending, Word> answers, String explanation) {
		if (prompt.suspicious()) {
			return;
		}
		words.forEach(word -> {
			Word answer = answers.get(word);
			if (answer != null) {
				cache.put(cacheKey(word, explanation), answer);
			}
		});
	}

	private static AiCacheKey cacheKey(Pending word, String explanation) {
		PartOfSpeech partOfSpeech = word.input().partOfSpeech();
		return AiCacheKey.vocabularyEnrichment(word.languageCode(), explanation, CACHE_VERSION, word.normalizedLemma(),
				partOfSpeech == null ? null : partOfSpeech.name());
	}

	/**
	 * Merges the answer into the word as it is now. A learner editing it at the same moment
	 * makes the save fail on its version; then it's merged again into the fresh word.
	 */
	private boolean merge(Pending word, Word answer) {
		for (int attempt = 1; attempt <= MERGE_ATTEMPTS; attempt++) {
			try {
				return Boolean.TRUE.equals(tx.execute(status -> items.findForEnrichmentById(word.id())
					.filter(item -> item.getAiStatus() == AiStatus.PENDING_AI)
					.map(item -> {
						EnrichmentRules.merge(item, answer, partOfSpeech -> partOfSpeechTaken(item, partOfSpeech));
						items.saveAndFlush(item);
						return true;
					})
					.orElse(false)));
			}
			catch (OptimisticLockingFailureException ex) {
				log.debug("Word {} changed while being enriched; merging again", word.id());
			}
		}
		log.warn("Word {} kept changing; enrichment not saved", word.id());
		return false;
	}

	/** Another of the user's words already has this lemma with that part of speech. */
	private boolean partOfSpeechTaken(VocabularyItem item, @Nullable PartOfSpeech partOfSpeech) {
		return partOfSpeech != null && items
			.findDuplicateId(item.getUserId(), item.getLanguageCode(), item.getNormalizedLemma(), partOfSpeech)
			.filter(other -> !other.equals(item.getId()))
			.isPresent();
	}

	private void markFailed(List<UUID> ids) {
		if (!ids.isEmpty()) {
			tx.executeWithoutResult(
					status -> items.markFailedForEnrichment(ids, AiStatus.PENDING_AI, AiStatus.AI_FAILED));
		}
	}

	private static List<UUID> itemIds(AiJob job) {
		Object ids = job.getPayload().get(EnrichmentQueue.ITEM_IDS);
		if (!(ids instanceof List<?> list)) {
			return List.of();
		}
		return list.stream().map(String::valueOf).map(UUID::fromString).toList();
	}

}
