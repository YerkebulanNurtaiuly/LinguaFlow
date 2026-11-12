package com.linguaflow.exercise;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.linguaflow.common.domain.ExerciseType;
import org.jspecify.annotations.Nullable;

/**
 * Published inside the transaction that stores an attempt (EX-05), for the progress
 * module (PRG-01). Listen with {@code @TransactionalEventListener} to act only once the
 * attempt is committed, or with {@code @EventListener} to update aggregates in the same
 * transaction. {@code practiceSessionId} is the practice session (PRC-02) it was made in, if any.
 */
public record ExerciseAttemptedEvent(UUID userId, String languageCode, UUID exerciseId, UUID attemptId,
		ExerciseType type, boolean correct, double score, Set<UUID> grammarTopicIds, Set<UUID> vocabularyItemIds,
		Instant attemptedAt, @Nullable UUID practiceSessionId) {

	public ExerciseAttemptedEvent {
		grammarTopicIds = Set.copyOf(grammarTopicIds);
		vocabularyItemIds = Set.copyOf(vocabularyItemIds);
	}

}
