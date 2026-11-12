package com.linguaflow.learning.practice;

import java.time.Instant;
import java.util.UUID;

import com.linguaflow.common.error.ConflictException;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.common.practice.PracticeSessionRecorder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks practice session items answered when a review or attempt names the session
 * (PRC-02). Kept apart from {@link PracticeSessionService}, which itself uses the review
 * service.
 */
@Component
class PracticeSessionTracker implements PracticeSessionRecorder {

	private final PracticeSessionRepository sessions;

	private final PracticeItems items;

	PracticeSessionTracker(PracticeSessionRepository sessions, PracticeItems items) {
		this.sessions = sessions;
		this.items = items;
	}

	@Override
	@Transactional(readOnly = true)
	public void requireOpen(UUID userId, UUID practiceSessionId) {
		requireInProgress(userId, practiceSessionId);
	}

	@Override
	@Transactional
	public void recordFlashcard(UUID userId, UUID practiceSessionId, UUID flashcardId, Instant at) {
		mark(userId, practiceSessionId, PracticeItemType.FLASHCARD, flashcardId, at, "card");
	}

	@Override
	@Transactional
	public void recordExercise(UUID userId, UUID practiceSessionId, UUID exerciseId, Instant at) {
		mark(userId, practiceSessionId, PracticeItemType.EXERCISE, exerciseId, at, "exercise");
	}

	private void mark(UUID userId, UUID sessionId, PracticeItemType type, UUID refId, Instant at, String what) {
		requireInProgress(userId, sessionId);
		if (items.markAnswered(sessionId, type, refId, at) == 0) {
			throw new InvalidFieldException("practiceSessionId", "doesn't include this " + what);
		}
	}

	private void requireInProgress(UUID userId, UUID sessionId) {
		if (!sessions.requireOwned(sessionId, userId).inProgress()) {
			throw new ConflictException("This practice session is already finished.");
		}
	}

}
