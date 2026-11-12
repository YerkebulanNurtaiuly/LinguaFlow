package com.linguaflow.exercise;

import java.util.Optional;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Exercise sets, always scoped by owner (AUTH-05). */
public interface ExerciseSetRepository extends OwnedRepository<ExerciseSet> {

	/** The user's sets in one language (EX-02). */
	Page<ExerciseSet> findAllByUserIdAndLanguageCode(UUID userId, String languageCode, Pageable pageable);

	/** A set for the generation worker (EX-02); system-wide, the job knows the owner. */
	@Query("select s from ExerciseSet s where s.id = :id")
	Optional<ExerciseSet> findForGenerationById(@Param("id") UUID id);

}
