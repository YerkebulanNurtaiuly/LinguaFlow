package com.linguaflow.learning.practice;

import java.util.Optional;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Practice sessions, always scoped by owner (AUTH-05). */
public interface PracticeSessionRepository extends OwnedRepository<PracticeSession> {

	/** The user's sessions in one language (PRC-02). */
	Page<PracticeSession> findAllByUserIdAndLanguageCode(UUID userId, String languageCode, Pageable pageable);

	/** The latest session in a status, e.g. one to continue (FE-PRC-01). */
	Optional<PracticeSession> findFirstByUserIdAndLanguageCodeAndStatusOrderByStartedAtDesc(UUID userId,
			String languageCode, PracticeSessionStatus status);

}
