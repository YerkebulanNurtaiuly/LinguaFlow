package com.linguaflow.flashcard;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Published inside the transaction that records a review (SRS-02), for the progress
 * module (PRG-01); a replayed review publishes nothing. Listen with
 * {@code @TransactionalEventListener} to act only once the review is committed.
 * @param remembered any grade but AGAIN
 */
public record FlashcardReviewedEvent(UUID userId, UUID flashcardId, UUID vocabularyItemId, UUID reviewId,
		ReviewGrade grade, boolean remembered, int intervalAfter, Instant reviewedAt,
		@Nullable UUID practiceSessionId) {
}
