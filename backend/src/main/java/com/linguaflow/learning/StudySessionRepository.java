package com.linguaflow.learning;

import java.util.Optional;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Scoped by owner (AUTH-05); every query takes the user. */
public interface StudySessionRepository extends OwnedRepository<StudySession> {

	/** Behind {@link #findActive}: at most one active session per user and language. */
	String ONE_ACTIVE_CONSTRAINT = "study_sessions_one_active_uq";

	Page<StudySession> findAllByUserIdAndLanguageCode(UUID userId, String languageCode, Pageable pageable);

	@Query("""
			select s from StudySession s
			where s.userId = :userId and s.languageCode = :languageCode and s.active = true
			""")
	Optional<StudySession> findActive(@Param("userId") UUID userId, @Param("languageCode") String languageCode);

	/**
	 * Ends the user's active session in the language, except {@code keep}. A bulk update, so
	 * it doesn't bump versions; it runs before activating another session because the
	 * unique index allows only one active session.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update StudySession s set s.active = false
			where s.userId = :userId and s.languageCode = :languageCode and s.active = true and s.id <> :keep
			""")
	int deactivateOthers(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("keep") UUID keep);

}
