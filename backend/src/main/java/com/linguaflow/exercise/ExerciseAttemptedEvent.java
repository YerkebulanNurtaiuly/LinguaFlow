package com.linguaflow.exercise;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.linguaflow.common.domain.ExerciseType;

/**
 * Published inside the transaction that stores an attempt (EX-05), for the progress
 * module (PRG-01). Listen with {@code @TransactionalEventListener} to act only once the
 * attempt is committed, or with {@code @EventListener} to update aggregates in the same
 * transaction.
 */
public record ExerciseAttemptedEvent(UUID userId, String languageCode, UUID exerciseId, UUID attemptId,
		ExerciseType type, boolean correct, double score, Set<UUID> grammarTopicIds, Set<UUID> vocabularyItemIds,
		Instant attemptedAt) {

	public ExerciseAttemptedEvent {
		grammarTopicIds = Set.copyOf(grammarTopicIds);
		vocabularyItemIds = Set.copyOf(vocabularyItemIds);
	}

}
