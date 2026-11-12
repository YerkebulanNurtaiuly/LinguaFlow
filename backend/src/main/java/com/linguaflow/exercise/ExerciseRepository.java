package com.linguaflow.exercise;

import java.util.List;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;

/** Exercises, always scoped by owner (AUTH-05). */
public interface ExerciseRepository extends OwnedRepository<Exercise> {

	/** A set's exercises in order. */
	List<Exercise> findAllByUserIdAndExerciseSetIdOrderByPosition(UUID userId, UUID exerciseSetId);

}
