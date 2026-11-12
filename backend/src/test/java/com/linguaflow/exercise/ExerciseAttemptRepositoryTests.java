package com.linguaflow.exercise;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.common.persistence.OwnedRepository;
import com.linguaflow.testsupport.OwnedRepositoryContract;
import com.linguaflow.testsupport.OwnedRepositoryTest;
import org.springframework.beans.factory.annotation.Autowired;

@OwnedRepositoryTest
class ExerciseAttemptRepositoryTests extends OwnedRepositoryContract<ExerciseAttempt> {

	@Autowired
	private ExerciseAttemptRepository attempts;

	@Autowired
	private ExerciseSetRepository sets;

	@Autowired
	private ExerciseRepository exercises;

	@Override
	protected OwnedRepository<ExerciseAttempt> repository() {
		return attempts;
	}

	@Override
	protected ExerciseAttempt newEntity(UUID userId, int n) {
		return newAttempt(sets, exercises, userId);
	}

	static ExerciseAttempt newAttempt(ExerciseSetRepository sets, ExerciseRepository exercises, UUID userId) {
		ExerciseSet set = sets.saveAndFlush(ExerciseSetRepositoryTests.newSet(userId, 1));
		ExerciseContent content = ExerciseSamples.of(ExerciseType.MULTIPLE_CHOICE);
		Exercise exercise = exercises.saveAndFlush(new Exercise(set, 0, content, null, List.of(), List.of(), null));
		ExerciseAnswer answer = new ExerciseAnswer.Choice(0);
		return new ExerciseAttempt(exercise, answer, AnswerEvaluator.evaluate(content, answer,
				AnswerEvaluator.Options.DEFAULT), Instant.parse("2026-11-12T08:00:00Z"), 1200);
	}

}
