package com.linguaflow.exercise;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.linguaflow.ai.InvalidLlmOutputException;
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
import com.linguaflow.ai.prompt.RenderedPrompt;
import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.practice.WordLessons;
import com.linguaflow.grammar.GrammarTopicService;
import com.linguaflow.grammar.GrammarTopicSummary;
import com.linguaflow.grammar.TitleNormalizer;
import com.linguaflow.user.UserLanguage;
import com.linguaflow.user.UserLanguageService;
import com.linguaflow.vocabulary.KnownWord;
import com.linguaflow.vocabulary.LemmaNormalizer;
import com.linguaflow.vocabulary.VocabularyService;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes an exercise set in the background (EX-02).
 * <ol>
 * <li><b>Material:</b> the set's grammar topics, and the learner's own enriched words:
 * the ones they're still learning become the practice words, the rest are known words
 * the model may use freely. Recent exercises on the same topics are listed so they
 * aren't repeated.</li>
 * <li><b>Types:</b> the requested count is split across the requested types (10 over
 * three types is 4, 3, 3), with one call per type, since each type has its own
 * template.</li>
 * <li><b>Checking:</b> each generated exercise is validated on its own (Bean Validation
 * on its records, then {@link ExerciseRules}), and repeats are dropped. Missing ones are
 * asked for again, at most {@value #ROUNDS} calls per type, each listing what's already
 * there. An answer that stays invalid after StructuredLlm's own retry counts as a round
 * with nothing usable.</li>
 * <li><b>Links:</b> the words and topics the model names are matched back to the
 * learner's words (by normalized lemma) and the set's topics (by normalized title). An
 * exercise naming no known topic is linked to all of the set's topics.</li>
 * <li><b>Saving:</b> the exercises, with types interleaved, and the set becomes READY in
 * one transaction; with none usable it becomes FAILED. A set that's gone or already
 * finished is skipped, so a rerun is harmless.</li>
 * </ol>
 * Provider errors, the quota and an open circuit propagate, so the queue retries or
 * postpones the job; {@link #onGiveUp} marks the set FAILED.
 */
@Component
class ExerciseGenerator implements AiJobHandler {

	private static final Logger log = LoggerFactory.getLogger(ExerciseGenerator.class);

	/** The job payload key holding the set ID. */
	static final String SET_ID = "exerciseSetId";

	/** Calls per exercise type at most: the first plus top-ups. */
	static final int ROUNDS = 3;

	/** How many of the learner's words are read, and how many go into the prompt. */
	static final int WORDS_READ = 300;

	static final int MAX_PRACTICE_WORDS = 50;

	static final int MAX_KNOWN_WORDS = 100;

	static final int RECENT_EXERCISES = 30;

	/** A translation in brackets at the end, as the words are listed: "der Hund (dog)". */
	private static final Pattern TRAILING_BRACKETS = Pattern.compile("\\s*\\([^()]*\\)\\s*$");

	private static final Set<KnowledgeStatus> STILL_LEARNING = EnumSet.of(KnowledgeStatus.NEW,
			KnowledgeStatus.LEARNING);

	private final ExerciseSetRepository sets;

	private final ExerciseRepository exercises;

	private final GrammarTopicService topics;

	private final VocabularyService vocabulary;

	private final UserLanguageService userLanguages;

	private final StructuredLlm llm;

	private final PromptLibrary prompts;

	private final Validator validator;

	private final TransactionTemplate tx;

	private final ApplicationEventPublisher events;

	private final WordLessons lessons;

	ExerciseGenerator(ExerciseSetRepository sets, ExerciseRepository exercises, GrammarTopicService topics,
			VocabularyService vocabulary, UserLanguageService userLanguages, StructuredLlm llm, PromptLibrary prompts,
			Validator validator, PlatformTransactionManager transactions, ApplicationEventPublisher events,
			WordLessons lessons) {
		this.lessons = lessons;
		this.events = events;
		this.sets = sets;
		this.exercises = exercises;
		this.topics = topics;
		this.vocabulary = vocabulary;
		this.userLanguages = userLanguages;
		this.llm = llm;
		this.prompts = prompts;
		this.validator = validator;
		this.tx = new TransactionTemplate(transactions);
	}

	/** Everything the prompts need, read up front. */
	record Material(UUID userId, String languageCode, String explanationLanguage, ExerciseSetRequest request,
			List<GrammarTopicSummary> topics, List<KnownWord> practiceWords, List<KnownWord> knownWords,
			String cefrLevel, List<String> recentExercises, boolean combine, List<String> lessonVocabulary) {
	}

	/** An exercise that passed every check, with its links resolved. */
	record Accepted(ExerciseContent content, Set<UUID> grammarTopicIds, Set<UUID> vocabularyItemIds) {
	}

	@Override
	public AiJobType type() {
		return AiJobType.EXERCISE_GENERATION;
	}

	@Override
	public Map<String, Object> handle(AiJob job) {
		UUID setId = setId(job);
		Optional<ExerciseSet> set = tx.execute(status -> sets.findForGenerationById(setId)
			.filter(found -> found.getUserId().equals(job.getUserId())));
		if (set == null || set.isEmpty()) {
			return Map.of("skipped", "the set was deleted");
		}
		if (set.get().getStatus() != ExerciseSetStatus.GENERATING) {
			return Map.of("skipped", "the set was already " + set.get().getStatus());
		}
		Material material;
		try {
			material = material(set.get());
		}
		catch (ApiException ex) {
			// The learner removed the language meanwhile; the set went with it
			return Map.of("skipped", "the language was removed");
		}

		List<List<Accepted>> byType = new ArrayList<>();
		int dropped = 0;
		List<String> made = new ArrayList<>();
		Map<ExerciseType, Integer> quotas = quotas(material.request());
		for (Map.Entry<ExerciseType, Integer> quota : quotas.entrySet()) {
			Generated generated = generate(job, material, quota.getKey(), quota.getValue(), made);
			byType.add(generated.accepted());
			dropped += generated.dropped();
		}
		List<Accepted> ordered = interleave(byType);
		boolean saved = Boolean.TRUE.equals(tx.execute(status -> save(setId, material.request(), ordered)));
		if (!saved) {
			return Map.of("skipped", "the set was deleted");
		}
		log.info("Generated {} of {} exercises for set {} (job {}, {} dropped)", ordered.size(),
				material.request().count(), setId, job.getId(), dropped);
		Map<String, Object> result = new LinkedHashMap<>(Map.of("exerciseSetId", setId.toString(), "exercises",
				ordered.size(), "requested", material.request().count(), "dropped", dropped));
		if (material.combine()) {
			// How many really practise two or more topics (PRC-03)
			result.put("combined", ordered.stream().filter(exercise -> exercise.grammarTopicIds().size() > 1).count());
		}
		return result;
	}

	@Override
	public void onGiveUp(AiJob job) {
		UUID setId = setId(job);
		tx.executeWithoutResult(status -> sets.findForGenerationById(setId)
			.filter(set -> set.getStatus() == ExerciseSetStatus.GENERATING)
			.ifPresent(set -> {
				set.markFailed();
				sets.saveAndFlush(set);
				events.publishEvent(finished(set));
			}));
	}

	private Material material(ExerciseSet set) {
		UUID userId = set.getUserId();
		String language = set.getLanguageCode();
		String explanationLanguage = userLanguages.explanationLanguageFor(userId, language);
		List<GrammarTopicSummary> setTopics = topics.summaries(userId, language, set.getRequest().grammarTopicIds());

		List<KnownWord> words = vocabulary.knownWords(userId, language, WORDS_READ);
		List<UUID> focus = set.getRequest().vocabularyItemIds();
		// A practice session (PRC-02) names its words; otherwise the ones still being learned come first
		List<KnownWord> practice = new ArrayList<>(focus.isEmpty()
				? words.stream().filter(word -> STILL_LEARNING.contains(word.status())).limit(MAX_PRACTICE_WORDS).toList()
				: vocabulary.knownWords(userId, language, focus).stream().limit(MAX_PRACTICE_WORDS).toList());
		// Learners who have finished every word still practise the most recent ones
		words.stream()
			.filter(word -> !practice.contains(word))
			.limit(Math.max(0, Math.min(MAX_PRACTICE_WORDS, set.getRequest().count() * 2) - practice.size()))
			.forEach(practice::add);
		List<KnownWord> known = words.stream().filter(word -> !practice.contains(word)).limit(MAX_KNOWN_WORDS).toList();

		List<String> recent = tx.execute(status -> setTopics.isEmpty() ? List.<String>of()
				: exercises
					.findRecentOnTopics(userId, setTopics.stream().map(GrammarTopicSummary::id).toList(),
							PageRequest.of(0, RECENT_EXERCISES))
					.stream()
					.map(exercise -> exercise.content().summary())
					.toList());
		// Combined exercises (PRC-03) need two topics to combine
		boolean combine = set.getRequest().combined() && setTopics.size() > 1;
		List<String> byLesson = combine
				? byLesson(practice, lessons.latestLessons(userId, practice.stream().map(KnownWord::id).toList()))
				: List.of();
		return new Material(userId, language, explanationLanguage, set.getRequest(), setTopics, practice, known,
				level(userId, language, setTopics), recent == null ? List.of() : recent, combine, byLesson);
	}

	/**
	 * The practice words grouped by their latest lesson, one "Lesson: word, word" line each,
	 * in the words' order; empty unless they come from two or more lessons.
	 */
	static List<String> byLesson(List<KnownWord> words, Map<UUID, String> lessonOf) {
		Map<String, List<String>> groups = new LinkedHashMap<>();
		for (KnownWord word : words) {
			String lesson = lessonOf.get(word.id());
			if (lesson != null) {
				groups.computeIfAbsent(lesson, key -> new ArrayList<>()).add(word.lemma());
			}
		}
		if (groups.size() < 2) {
			return List.of();
		}
		return groups.entrySet()
			.stream()
			.map(group -> group.getKey() + ": " + String.join(", ", group.getValue()))
			.toList();
	}

	/** The highest level among the topics, or else the learner's own estimate. */
	private String level(UUID userId, String language, List<GrammarTopicSummary> setTopics) {
		Optional<CefrLevel> topicLevel = setTopics.stream()
			.map(GrammarTopicSummary::cefrLevel)
			.filter(Objects::nonNull)
			.max(Enum::compareTo);
		if (topicLevel.isPresent()) {
			return topicLevel.get().name();
		}
		return userLanguages.list(userId)
			.stream()
			.filter(entry -> entry.getLanguageCode().equals(language))
			.map(UserLanguage::getSelfAssessedLevel)
			.filter(Objects::nonNull)
			.map(Enum::name)
			.findFirst()
			.orElse(null);
	}

	/** {@code count} split over the types, the first types taking the remainder. */
	static Map<ExerciseType, Integer> quotas(ExerciseSetRequest request) {
		List<ExerciseType> types = request.types().isEmpty() ? List.of(ExerciseType.values()) : request.types();
		Map<ExerciseType, Integer> quotas = new LinkedHashMap<>();
		int share = request.count() / types.size();
		int extra = request.count() % types.size();
		for (int i = 0; i < types.size(); i++) {
			int quota = share + (i < extra ? 1 : 0);
			if (quota > 0) {
				quotas.put(types.get(i), quota);
			}
		}
		return quotas;
	}

	private record Generated(List<Accepted> accepted, int dropped) {
	}

	private Generated generate(AiJob job, Material material, ExerciseType type, int quota, List<String> made) {
		List<Accepted> accepted = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		material.recentExercises().forEach(summary -> seen.add(ExerciseRules.normalize(summary)));
		made.forEach(summary -> seen.add(ExerciseRules.normalize(summary)));
		int dropped = 0;
		for (int round = 1; round <= ROUNDS && accepted.size() < quota; round++) {
			GeneratedExercises.Batch batch;
			try {
				batch = llm
					.complete(job.caller(), prompt(material, type, quota - accepted.size(), made),
							GeneratedExercises.batchType(type), OutputCheck.none())
					.value();
			}
			catch (InvalidLlmOutputException ex) {
				log.warn("Exercise generation for set {} got no usable {} answer (round {}): {}",
						job.getPayload().get(SET_ID), type, round, ex.problems());
				continue;
			}
			for (GeneratedExercises.Item item : batch.exercises()) {
				if (accepted.size() == quota) {
					break;
				}
				Optional<ExerciseContent> content = check(type, item);
				if (content.isEmpty() || !seen.add(ExerciseRules.normalize(content.get().summary()))) {
					dropped++;
					continue;
				}
				accepted.add(new Accepted(content.get(), topicIds(material, item), wordIds(material, item)));
				made.add(content.get().summary());
			}
		}
		return new Generated(accepted, dropped);
	}

	private RenderedPrompt prompt(Material material, ExerciseType type, int count,
			List<String> made) {
		List<String> recent = new ArrayList<>(made);
		recent.addAll(material.recentExercises());
		return prompts.render(PromptTemplate.exercise(type),
				PromptVariables.forLanguages(material.languageCode(), material.explanationLanguage())
					.set(PromptVar.COUNT, String.valueOf(count))
					.set(PromptVar.CEFR_LEVEL, material.cefrLevel())
					.set(PromptVar.DIFFICULTY, describe(material.request().difficulty()))
					.set(PromptVar.FOCUS_TOPICS, material.topics().stream().map(ExerciseGenerator::describe).toList())
					.set(PromptVar.PRACTICE_VOCABULARY,
							material.practiceWords().stream().map(ExerciseGenerator::describe).toList())
					.set(PromptVar.KNOWN_VOCABULARY,
							material.knownWords().stream().map(ExerciseGenerator::describe).toList())
					.set(PromptVar.LESSON_VOCABULARY, material.lessonVocabulary())
					.set(PromptVar.COMBINE, material.combine() ? "yes" : null)
					.set(PromptVar.RECENT_EXERCISES, recent.stream().limit(RECENT_EXERCISES).toList()));
	}

	private static String describe(GrammarTopicSummary topic) {
		return topic.description() == null || topic.description().isBlank() ? topic.title()
				: topic.title() + ": " + topic.description();
	}

	private static String describe(KnownWord word) {
		return word.lemma() + " (" + word.translation() + ")";
	}

	private static String describe(ExerciseDifficulty difficulty) {
		if (difficulty == null) {
			return null;
		}
		return switch (difficulty) {
			case EASY -> "easy: short, simple sentences and the most common forms";
			case MEDIUM -> "medium: everyday sentences of normal length";
			case HARD -> "hard: longer sentences, less common forms and closer distractors";
		};
	}

	/** The item's content if it passes every check. */
	private Optional<ExerciseContent> check(ExerciseType type, GeneratedExercises.Item item) {
		if (item == null || item.prompt() == null || item.solution() == null || item.words() == null
				|| item.topics() == null) {
			return Optional.empty();
		}
		if (!validator.validate(item.prompt()).isEmpty() || !validator.validate(item.solution()).isEmpty()) {
			return Optional.empty();
		}
		ExerciseContent content = new ExerciseContent(type, item.prompt(), item.solution());
		return ExerciseRules.problems(content).isEmpty() ? Optional.of(content) : Optional.empty();
	}

	private static Set<UUID> topicIds(Material material, GeneratedExercises.Item item) {
		Map<String, UUID> byTitle = new HashMap<>();
		material.topics().forEach(topic -> byTitle.put(topic.normalizedTitle(), topic.id()));
		Set<UUID> ids = new LinkedHashSet<>();
		for (String title : item.topics()) {
			if (title == null) {
				continue;
			}
			// The topics are listed as "Title: description"
			String bare = title.contains(":") ? title.substring(0, title.indexOf(':')) : title;
			UUID id = byTitle.get(TitleNormalizer.normalize(material.languageCode(), bare));
			if (id != null) {
				ids.add(id);
			}
		}
		if (ids.isEmpty()) {
			material.topics().forEach(topic -> ids.add(topic.id()));
		}
		return ids;
	}

	private static Set<UUID> wordIds(Material material, GeneratedExercises.Item item) {
		Map<String, UUID> byLemma = new HashMap<>();
		material.knownWords().forEach(word -> byLemma.putIfAbsent(word.normalizedLemma(), word.id()));
		material.practiceWords().forEach(word -> byLemma.put(word.normalizedLemma(), word.id()));
		Set<UUID> ids = new LinkedHashSet<>();
		for (String word : item.words()) {
			if (word == null) {
				continue;
			}
			String bare = TRAILING_BRACKETS.matcher(word).replaceFirst("");
			UUID id = byLemma.get(LemmaNormalizer.normalize(material.languageCode(), bare));
			if (id != null) {
				ids.add(id);
			}
		}
		return ids;
	}

	/** One of each type in turn, so a set doesn't run through its types in blocks. */
	static <T> List<T> interleave(List<List<T>> lists) {
		List<T> result = new ArrayList<>();
		for (int i = 0; result.size() < lists.stream().mapToInt(List::size).sum(); i++) {
			for (List<T> list : lists) {
				if (i < list.size()) {
					result.add(list.get(i));
				}
			}
		}
		return result;
	}

	private boolean save(UUID setId, ExerciseSetRequest request, List<Accepted> accepted) {
		Optional<ExerciseSet> found = sets.findForGenerationById(setId);
		if (found.isEmpty()) {
			return false;
		}
		ExerciseSet set = found.get();
		if (set.getStatus() != ExerciseSetStatus.GENERATING) {
			return true;
		}
		for (int position = 0; position < accepted.size(); position++) {
			Accepted exercise = accepted.get(position);
			exercises.save(new Exercise(set, position, exercise.content(), request.difficulty(),
					exercise.grammarTopicIds(), exercise.vocabularyItemIds(), null));
		}
		if (accepted.isEmpty()) {
			set.markFailed();
		}
		else {
			set.markReady();
		}
		// Flushed, so a practice session can link the new exercises with plain SQL
		sets.saveAndFlush(set);
		events.publishEvent(finished(set));
		return true;
	}

	private static ExerciseSetFinishedEvent finished(ExerciseSet set) {
		return new ExerciseSetFinishedEvent(set.getUserId(), set.getId(), set.getPracticeSessionId(), set.getStatus());
	}

	private static UUID setId(AiJob job) {
		Object id = job.getPayload().get(SET_ID);
		if (id == null) {
			throw new AiJobException("The job has no " + SET_ID, false);
		}
		return UUID.fromString(id.toString());
	}

}
