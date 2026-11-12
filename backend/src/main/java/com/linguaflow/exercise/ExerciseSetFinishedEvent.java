package com.linguaflow.exercise;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Published inside the transaction that marks a set READY or FAILED (EX-02), so a
 * practice session (PRC-02) waiting for its exercises can add them in the same
 * transaction.
 * @param practiceSessionId the practice session the set was generated for, if any
 */
public record ExerciseSetFinishedEvent(UUID userId, UUID exerciseSetId, @Nullable UUID practiceSessionId,
		ExerciseSetStatus status) {
}
