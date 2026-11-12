package com.linguaflow.exercise;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
	 */
	public record NewExerciseSet(List<UUID> grammarTopicIds, @Nullable List<ExerciseType> types,
			@Nullable Integer count, @Nullable ExerciseDifficulty difficulty) {
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
		int count = request.count() == null ? DEFAULT_COUNT : request.count();
		if (count < 1 || count > MAX_COUNT) {
			throw new InvalidFieldException("count", "must be between 1 and " + MAX_COUNT);
		}
		ExerciseSet set = sets.save(new ExerciseSet(userId, language, ExerciseSetSource.MANUAL,
				new ExerciseSetRequest(topicIds, types, count, request.difficulty()), null));
		UUID jobId = aiJobs
			.enqueue(AiJobType.EXERCISE_GENERATION, userId, Map.of(ExerciseGenerator.SET_ID, set.getId().toString()))
			.getId();
		return new Requested(set, jobId);
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
