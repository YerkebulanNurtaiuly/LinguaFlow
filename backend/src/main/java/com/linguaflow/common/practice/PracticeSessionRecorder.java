package com.linguaflow.common.practice;

import java.time.Instant;
import java.util.UUID;

/**
 * Links reviews and exercise attempts to the practice session they were made in
 * (PRC-02). The flashcard and exercise modules call it; the practice module implements
 * it, so neither depends on the other.
 */
public interface PracticeSessionRecorder {

	/**
	 * Checks that the session can take answers.
	 * @throws com.linguaflow.common.error.NotFoundException when it isn't the user's
	 * @throws com.linguaflow.common.error.ApiException 409 {@code CONFLICT} when it's
	 * finished
	 */
	void requireOpen(UUID userId, UUID practiceSessionId);

	/**
	 * Marks the session's item for this card answered; call it in the transaction that
	 * stores the review.
	 * @throws com.linguaflow.common.error.InvalidFieldException on
	 * {@code practiceSessionId} when the session doesn't include the card
	 */
	void recordFlashcard(UUID userId, UUID practiceSessionId, UUID flashcardId, Instant at);

	/** Like {@link #recordFlashcard}, for an exercise. */
	void recordExercise(UUID userId, UUID practiceSessionId, UUID exerciseId, Instant at);

}
