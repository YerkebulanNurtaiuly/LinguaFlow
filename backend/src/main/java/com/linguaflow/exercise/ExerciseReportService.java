package com.linguaflow.exercise;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.common.error.InvalidFieldException;
import org.hibernate.Hibernate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Report a bad exercise" (EX-07). A reported exercise keeps its attempts, is marked
 * {@code reported} in its set, and is left out of practice
 * ({@link ExerciseRepository#findAllByUserIdAndExerciseSetIdAndReportedAtIsNullOrderByPosition}).
 * Each report is logged (exercise, type, set; never the learner's reason) and listed
 * for admins with its prompt and solution, to tune the generation prompts.
 */
@Service
public class ExerciseReportService {

	private static final Logger log = LoggerFactory.getLogger(ExerciseReportService.class);

	private final ExerciseRepository exercises;

	private final Clock clock;

	ExerciseReportService(ExerciseRepository exercises, Clock clock) {
		this.exercises = exercises;
		this.clock = clock;
	}

	/**
	 * Flags the user's exercise as bad; reporting again updates the reason.
	 * @throws InvalidFieldException on {@code reason} when it's too long
	 */
	@Transactional
	public Exercise report(UUID userId, UUID exerciseId, @Nullable String reason) {
		Exercise exercise = exercises.requireOwned(exerciseId, userId);
		if (reason != null && reason.strip().length() > Exercise.MAX_REPORT_REASON) {
			throw new InvalidFieldException("reason", "must be at most " + Exercise.MAX_REPORT_REASON + " characters");
		}
		boolean first = !exercise.isReported();
		exercise.report(reason, clock.instant());
		if (first) {
			log.info("Exercise {} ({}, set {}) was reported as bad", exercise.getId(), exercise.getType(),
					exercise.getExerciseSetId());
		}
		return loaded(exercises.saveAndFlush(exercise));
	}

	/** Takes the user's report back. */
	@Transactional
	public Exercise withdraw(UUID userId, UUID exerciseId) {
		Exercise exercise = exercises.requireOwned(exerciseId, userId);
		if (exercise.isReported()) {
			exercise.withdrawReport();
			log.info("The report on exercise {} was withdrawn", exercise.getId());
		}
		return loaded(exercises.saveAndFlush(exercise));
	}

	/** Every user's reported exercises, latest first; for admins. */
	@Transactional(readOnly = true)
	public Page<ReportedExercise> reported(@Nullable Collection<ExerciseType> types, Pageable pageable) {
		Collection<ExerciseType> filter = types == null || types.isEmpty() ? List.of(ExerciseType.values()) : types;
		Page<ReportedExercise> page = exercises.findReportedForTuning(filter, pageable);
		page.forEach(reported -> loaded(reported.exercise()));
		return page;
	}

	/** Loads the links, since open-in-view is off. */
	private static Exercise loaded(Exercise exercise) {
		Hibernate.initialize(exercise.getGrammarTopicIdsForLoading());
		Hibernate.initialize(exercise.getVocabularyItemIdsForLoading());
		return exercise;
	}

}
