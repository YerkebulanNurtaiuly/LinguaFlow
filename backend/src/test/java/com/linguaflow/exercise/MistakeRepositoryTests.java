package com.linguaflow.exercise;

import java.time.Instant;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;
import com.linguaflow.testsupport.OwnedRepositoryContract;
import com.linguaflow.testsupport.OwnedRepositoryTest;
import org.springframework.beans.factory.annotation.Autowired;

@OwnedRepositoryTest
class MistakeRepositoryTests extends OwnedRepositoryContract<Mistake> {

	@Autowired
	private MistakeRepository mistakes;

	@Autowired
	private ExerciseAttemptRepository attempts;

	@Autowired
	private ExerciseSetRepository sets;

	@Autowired
	private ExerciseRepository exercises;

	@Override
	protected OwnedRepository<Mistake> repository() {
		return mistakes;
	}

	@Override
	protected Mistake newEntity(UUID userId, int n) {
		ExerciseAttempt attempt = attempts
			.saveAndFlush(ExerciseAttemptRepositoryTests.newAttempt(sets, exercises, userId));
		return new Mistake(attempt, Mistake.WRONG_CHOICE, null, null, "die", null,
				Instant.parse("2026-11-12T08:00:00Z"));
	}

}
