package com.linguaflow.exercise;

import java.util.List;
import java.util.UUID;

import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.common.persistence.OwnedRepository;
import com.linguaflow.testsupport.OwnedRepositoryContract;
import com.linguaflow.testsupport.OwnedRepositoryTest;
import org.springframework.beans.factory.annotation.Autowired;

@OwnedRepositoryTest
class ExerciseRepositoryTests extends OwnedRepositoryContract<Exercise> {

	@Autowired
	private ExerciseRepository exercises;

	@Autowired
	private ExerciseSetRepository sets;

	@Override
	protected OwnedRepository<Exercise> repository() {
		return exercises;
	}

	@Override
	protected Exercise newEntity(UUID userId, int n) {
		ExerciseSet set = sets.saveAndFlush(ExerciseSetRepositoryTests.newSet(userId, 1));
		return new Exercise(set, 0, ExerciseSamples.of(ExerciseType.values()[n % ExerciseType.values().length]),
				null, List.of(), List.of(), null);
	}

}
