package com.linguaflow.exercise;

import java.util.List;
import java.util.UUID;

import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.common.persistence.OwnedRepository;
import com.linguaflow.testsupport.OwnedRepositoryContract;
import com.linguaflow.testsupport.OwnedRepositoryTest;
import org.springframework.beans.factory.annotation.Autowired;

@OwnedRepositoryTest
class ExerciseSetRepositoryTests extends OwnedRepositoryContract<ExerciseSet> {

	@Autowired
	private ExerciseSetRepository sets;

	@Override
	protected OwnedRepository<ExerciseSet> repository() {
		return sets;
	}

	@Override
	protected ExerciseSet newEntity(UUID userId, int n) {
		return newSet(userId, n);
	}

	static ExerciseSet newSet(UUID userId, int count) {
		return new ExerciseSet(userId, "de", ExerciseSetSource.MANUAL,
				new ExerciseSetRequest(List.of(), List.of(ExerciseType.FILL_BLANK), count, ExerciseDifficulty.EASY),
				null);
	}

}
