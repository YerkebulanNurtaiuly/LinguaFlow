package com.linguaflow.exercise;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.linguaflow.ai.job.AiJobQueue;
import com.linguaflow.ai.job.AiJobType;
import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.grammar.GrammarTopicService;
import com.linguaflow.user.ActiveLanguageService;
import com.linguaflow.user.UserLanguageService;
import org.hibernate.Hibernate;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exercise sets (EX-02): asking for one queues its generation; the set is READY or FAILED
 * once the job is done. Sets belong to the active language.
 */
@Service
public class ExerciseSetService {

	public static final int MAX_TOPICS = 5;

	public static final int MAX_COUNT = 20;

	public static final int DEFAULT_COUNT = 10;

	private final ExerciseSetRepository sets;

	private final ExerciseRepository exercises;

	private final ActiveLanguageService activeLanguages;

	private final UserLanguageService userLanguages;

	private final GrammarTopicService topics;

	private final AiJobQueue aiJobs;

	ExerciseSetService(ExerciseSetRepository sets, ExerciseRepository exercises, ActiveLanguageService activeLanguages,
			UserLanguageService userLanguages, GrammarTopicService topics, AiJobQueue aiJobs) {
		this.sets = sets;
		this.exercises = exercises;
		this.activeLanguages = activeLanguages;
		this.userLanguages = userLanguages;
		this.topics = topics;
		this.aiJobs = aiJobs;
	}

	/**
	 * What the learner asks for.
	 * @param types empty means the learner's preferred types, or else every type
	 * @param count null means {@value #DEFAULT_COUNT}
	 * @param combined each exercise combines several of the topics (PRC-03); needs two or more
	 */
	public record NewExerciseSet(List<UUID> grammarTopicIds, @Nullable List<ExerciseType> types,
			@Nullable Integer count, @Nullable ExerciseDifficulty difficulty, boolean combined) {

		public NewExerciseSet(List<UUID> grammarTopicIds, @Nullable List<ExerciseType> types, @Nullable Integer count,
				@Nullable ExerciseDifficulty difficulty) {
			this(grammarTopicIds, types, count, difficulty, false);
		}

	}

	/** A set just asked for, and the job writing it. */
	public record Requested(ExerciseSet set, UUID aiJobId) {
	}

	/** A set with its exercises in order. */
	public record SetWithExercises(ExerciseSet set, List<Exercise> exercises) {
	}

	/** A set with how many exercises it has. */
	public record SetSummary(ExerciseSet set, long exerciseCount) {
	}

	/**
	 * Saves a GENERATING set in the active language and queues its generation in the same
	 * transaction.
	 * @throws InvalidFieldException on {@code grammarTopicIds} when a topic isn't the
	 * learner's or is in another language
	 */
	@Transactional
	public Requested create(UUID userId, NewExerciseSet request) {
		String language = activeLanguages.requireActive(userId);
		List<UUID> topicIds = List.copyOf(new LinkedHashSet<>(request.grammarTopicIds()));
		if (topicIds.isEmpty() || topicIds.size() > MAX_TOPICS) {
			throw new InvalidFieldException("grammarTopicIds", "must name 1 to " + MAX_TOPICS + " grammar topics");
		}
		if (topics.summaries(userId, language, topicIds).size() != topicIds.size()) {
			throw new InvalidFieldException("grammarTopicIds",
					"contains a topic that isn't yours or belongs to another language");
		}
		List<ExerciseType> types = request.types() == null ? List.of()
				: List.copyOf(new LinkedHashSet<>(request.types()));
		if (types.isEmpty()) {
			types = userLanguages.settingsFor(userId, language).preferredExerciseTypes();
		}
		if (request.combined() && topicIds.size() < 2) {
			throw new InvalidFieldException("combined", "needs at least two grammar topics");
		}
		int count = request.count() == null ? DEFAULT_COUNT : request.count();
		if (count < 1 || count > MAX_COUNT) {
			throw new InvalidFieldException("count", "must be between 1 and " + MAX_COUNT);
		}
		ExerciseSet set = sets.save(new ExerciseSet(userId, language, ExerciseSetSource.MANUAL,
				new ExerciseSetRequest(topicIds, types, count, request.difficulty(), List.of(), request.combined()), null));
		UUID jobId = aiJobs
			.enqueue(AiJobType.EXERCISE_GENERATION, userId, Map.of(ExerciseGenerator.SET_ID, set.getId().toString()))
			.getId();
		return new Requested(set, jobId);
	}

	/**
	 * Saves a GENERATING set for a practice session (PRC-02) and queues its generation, in
	 * the caller's transaction. The material comes from the practice selection, so it's
	 * the user's own; the formats are the learner's preferred ones.
	 * @param vocabularyItemIds words to practise first
	 */
	@Transactional
	public Requested createForPractice(UUID userId, String languageCode, UUID practiceSessionId,
			List<UUID> grammarTopicIds, List<UUID> vocabularyItemIds, int count) {
		if (grammarTopicIds.isEmpty() || grammarTopicIds.size() > MAX_TOPICS || count < 1 || count > MAX_COUNT) {
			throw new IllegalArgumentException("1 to " + MAX_TOPICS + " topics and 1 to " + MAX_COUNT + " exercises");
		}
		List<ExerciseType> types = userLanguages.settingsFor(userId, languageCode).preferredExerciseTypes();
		ExerciseSet set = sets.save(new ExerciseSet(userId, languageCode, ExerciseSetSource.PRACTICE,
				new ExerciseSetRequest(grammarTopicIds, types, count, null, vocabularyItemIds, grammarTopicIds.size() > 1),
				practiceSessionId));
		UUID jobId = aiJobs
			.enqueue(AiJobType.EXERCISE_GENERATION, userId, Map.of(ExerciseGenerator.SET_ID, set.getId().toString()))
			.getId();
		return new Requested(set, jobId);
	}

	/**
	 * Saves a GENERATING WEAK_SPOTS set (PRG-06) in the active language and queues its
	 * generation: the learner's weakest topics and words, and their frequent mistakes to
	 * test, as the weak-point analysis chose them, so they're the user's own.
	 * @param types empty means the learner's preferred types, or else every type
	 */
	@Transactional
	public Requested createForWeakSpots(UUID userId, List<UUID> grammarTopicIds, List<UUID> vocabularyItemIds,
			List<String> focusMistakes, List<ExerciseType> types, int count) {
		String language = activeLanguages.requireActive(userId);
		if (grammarTopicIds.isEmpty() || grammarTopicIds.size() > MAX_TOPICS || count < 1 || count > MAX_COUNT) {
			throw new IllegalArgumentException("1 to " + MAX_TOPICS + " topics and 1 to " + MAX_COUNT + " exercises");
		}
		List<ExerciseType> chosen = types.isEmpty() ? userLanguages.settingsFor(userId, language).preferredExerciseTypes()
				: List.copyOf(new LinkedHashSet<>(types));
		ExerciseSet set = sets.save(new ExerciseSet(userId, language, ExerciseSetSource.WEAK_SPOTS,
				new ExerciseSetRequest(grammarTopicIds, chosen, count, null, vocabularyItemIds, false, focusMistakes),
				null));
		UUID jobId = aiJobs
			.enqueue(AiJobType.EXERCISE_GENERATION, userId, Map.of(ExerciseGenerator.SET_ID, set.getId().toString()))
			.getId();
		return new Requested(set, jobId);
	}

	/**
	 * The set generated for a practice session, with its exercises in order (reported
	 * ones included); empty when the session asked for none.
	 */
	@Transactional(readOnly = true)
	public Optional<SetWithExercises> forPracticeSession(UUID userId, UUID practiceSessionId) {
		return sets.findFirstByUserIdAndPracticeSessionIdOrderByCreatedAtDesc(userId, practiceSessionId)
			.map(set -> get(userId, set.getId()));
	}

	@Transactional(readOnly = true)
	public SetWithExercises get(UUID userId, UUID id) {
		ExerciseSet set = sets.requireOwned(id, userId);
		List<Exercise> found = exercises.findAllByUserIdAndExerciseSetIdOrderByPosition(userId, id);
		// Load the links now (batched), since open-in-view is off
		found.forEach(exercise -> {
			Hibernate.initialize(exercise.getGrammarTopicIdsForLoading());
			Hibernate.initialize(exercise.getVocabularyItemIdsForLoading());
		});
		return new SetWithExercises(set, found);
	}

	/**
	 * The user's exercises among {@code ids}, with their links loaded; others are left
	 * out. For practice sessions that reuse exercises (FE-PRC-01).
	 */
	@Transactional(readOnly = true)
	public List<Exercise> exercises(UUID userId, List<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		List<Exercise> found = exercises.findAllByUserIdAndIdIn(userId, ids);
		found.forEach(exercise -> {
			Hibernate.initialize(exercise.getGrammarTopicIdsForLoading());
			Hibernate.initialize(exercise.getVocabularyItemIdsForLoading());
		});
		return found;
	}

	/** The active language's sets, with their exercise counts. */
	@Transactional(readOnly = true)
	public Page<SetSummary> list(UUID userId, Pageable pageable) {
		String language = activeLanguages.requireActive(userId);
		Page<ExerciseSet> page = sets.findAllByUserIdAndLanguageCode(userId, language, pageable);
		List<UUID> ids = new ArrayList<>(page.map(ExerciseSet::getId).getContent());
		Map<UUID, Long> counts = ids.isEmpty() ? Map.of()
				: exercises.countBySets(userId, ids)
					.stream()
					.collect(Collectors.toMap(ExerciseRepository.SetCount::getSetId,
							ExerciseRepository.SetCount::getCount));
		return page.map(set -> new SetSummary(set, counts.getOrDefault(set.getId(), 0L)));
	}

	/** Deletes a set with its exercises (and later their attempts). */
	@Transactional
	public void delete(UUID userId, UUID id) {
		sets.requireOwned(id, userId);
		sets.deleteByIdAndUserId(id, userId);
	}

}
