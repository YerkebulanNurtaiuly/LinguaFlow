package com.linguaflow.grammar;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.linguaflow.ai.OutputCheck;
import com.linguaflow.ai.StructuredLlm;
import com.linguaflow.ai.job.AiJob;
import com.linguaflow.ai.job.AiJobException;
import com.linguaflow.ai.job.AiJobHandler;
import com.linguaflow.ai.job.AiJobType;
import com.linguaflow.ai.prompt.PromptLibrary;
import com.linguaflow.ai.prompt.PromptTemplate;
import com.linguaflow.ai.prompt.PromptVar;
import com.linguaflow.ai.prompt.PromptVariables;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.common.error.ApiException;
import com.linguaflow.user.UserLanguageService;
import com.linguaflow.vocabulary.VocabularyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the AI explanation of a grammar topic (GRM-04) in the background.
 * <ul>
 * <li>The prompt carries the topic's title, description and level, in the learner's
 * explanation language. It also names topics the learner has learned and some of their
 * words, so examples use vocabulary they know. Because of that the answer is the
 * learner's own and isn't shared through the AI cache.</li>
 * <li>The answer replaces the topic's previous explanation; the learner's notes are never
 * touched. A topic deleted meanwhile is skipped.</li>
 * <li>Provider errors, the quota and an open circuit propagate, so the queue retries or
 * postpones the job (AI-04, AI-05). The job's status is how the UI learns of a
 * failure.</li>
 * </ul>
 */
@Component
class GrammarExplanationHandler implements AiJobHandler {

	private static final Logger log = LoggerFactory.getLogger(GrammarExplanationHandler.class);

	/** The job payload key holding the topic ID. */
	static final String TOPIC_ID = "grammarTopicId";

	/** How many of the learner's words and learned topics go into the prompt. */
	static final int KNOWN_WORDS = 30;

	static final int STUDIED_TOPICS = 15;

	private static final int SAVE_ATTEMPTS = 3;

	private final GrammarTopicRepository topics;

	private final StructuredLlm llm;

	private final PromptLibrary prompts;

	private final UserLanguageService userLanguages;

	private final VocabularyService vocabulary;

	private final Clock clock;

	private final TransactionTemplate tx;

	GrammarExplanationHandler(GrammarTopicRepository topics, StructuredLlm llm, PromptLibrary prompts,
			UserLanguageService userLanguages, VocabularyService vocabulary, Clock clock,
			PlatformTransactionManager transactions) {
		this.topics = topics;
		this.llm = llm;
		this.prompts = prompts;
		this.userLanguages = userLanguages;
		this.vocabulary = vocabulary;
		this.clock = clock;
		this.tx = new TransactionTemplate(transactions);
	}

	/** What the prompt needs, read in one short transaction. */
	private record Topic(UUID userId, String languageCode, String title, String description, String cefrLevel,
			List<String> studiedTopics) {
	}

	@Override
	public AiJobType type() {
		return AiJobType.GRAMMAR_EXPLANATION;
	}

	@Override
	public Map<String, Object> handle(AiJob job) {
		UUID topicId = topicId(job);
		Optional<Topic> topic = Objects.requireNonNull(tx.execute(status -> topics.findForExplanationById(topicId)
			.filter(found -> found.getUserId().equals(job.getUserId()))
			.map(found -> new Topic(found.getUserId(), found.getLanguageCode(), found.getTitle(),
					found.getDescription(), found.getCefrLevel() == null ? null : found.getCefrLevel().name(),
					topics.findTitlesByStatus(found.getUserId(), found.getLanguageCode(), found.getId(),
							EnumSet.of(KnowledgeStatus.LEARNED, KnowledgeStatus.MASTERED),
							PageRequest.of(0, STUDIED_TOPICS))))));
		if (topic.isEmpty()) {
			return Map.of("skipped", "the topic was deleted");
		}
		Topic about = topic.get();
		String explanationLanguage;
		try {
			explanationLanguage = userLanguages.explanationLanguageFor(about.userId(), about.languageCode());
		}
		catch (ApiException ex) {
			// The learner dropped the language meanwhile; the topic is gone with it
			return Map.of("skipped", "the language was removed");
		}
		var prompt = prompts.render(PromptTemplate.GRAMMAR_EXPLANATION,
				PromptVariables.forLanguages(about.languageCode(), explanationLanguage)
					.set(PromptVar.TOPIC_TITLE, about.title())
					.set(PromptVar.TOPIC_DESCRIPTION, about.description())
					.set(PromptVar.CEFR_LEVEL, about.cefrLevel())
					.set(PromptVar.FOCUS_TOPICS, about.studiedTopics())
					.set(PromptVar.KNOWN_VOCABULARY,
							vocabulary.recentLemmas(about.userId(), about.languageCode(), KNOWN_WORDS)));
		GrammarExplanation explanation = llm
			.complete(job.caller(), prompt, ExplanationAnswer.class, OutputCheck.none())
			.value()
			.toExplanation();
		if (!save(topicId, explanation)) {
			return Map.of("skipped", "the topic was deleted");
		}
		log.info("Wrote the AI explanation of grammar topic {} for job {}", topicId, job.getId());
		return Map.of("grammarTopicId", topicId.toString());
	}

	/**
	 * Stores the explanation on the topic as it is now. A learner editing the topic at the
	 * same moment makes the save fail on its version; then it's stored again on the fresh
	 * topic.
	 */
	private boolean save(UUID topicId, GrammarExplanation explanation) {
		for (int attempt = 1; attempt <= SAVE_ATTEMPTS; attempt++) {
			try {
				return Boolean.TRUE.equals(tx.execute(status -> topics.findForExplanationById(topicId).map(topic -> {
					topic.setAiExplanation(explanation, clock.instant());
					topics.saveAndFlush(topic);
					return true;
				}).orElse(false)));
			}
			catch (OptimisticLockingFailureException ex) {
				log.debug("Grammar topic {} changed while its explanation was written; saving again", topicId);
			}
		}
		// Retrying the whole job would pay for another answer; this is rare enough to fail
		throw new AiJobException("The topic kept changing; the explanation wasn't saved", false);
	}

	private static UUID topicId(AiJob job) {
		Object id = job.getPayload().get(TOPIC_ID);
		if (id == null) {
			throw new AiJobException("The job has no " + TOPIC_ID, false);
		}
		return UUID.fromString(id.toString());
	}

}
