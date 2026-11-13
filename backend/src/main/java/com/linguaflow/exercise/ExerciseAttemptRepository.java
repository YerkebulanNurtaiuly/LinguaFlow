package com.linguaflow.exercise;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Exercise attempts, always scoped by owner (AUTH-05). */
public interface ExerciseAttemptRepository extends OwnedRepository<ExerciseAttempt> {

	/** One exercise's attempts (EX-05). */
	Page<ExerciseAttempt> findAllByUserIdAndExerciseId(UUID userId, UUID exerciseId, Pageable pageable);

	/** The user's attempts among {@code ids} (PRG-04). */
	List<ExerciseAttempt> findAllByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

}
