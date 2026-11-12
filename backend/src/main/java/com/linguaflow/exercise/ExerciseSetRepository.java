package com.linguaflow.exercise;

import com.linguaflow.common.persistence.OwnedRepository;

/** Exercise sets, always scoped by owner (AUTH-05). */
public interface ExerciseSetRepository extends OwnedRepository<ExerciseSet> {

}
