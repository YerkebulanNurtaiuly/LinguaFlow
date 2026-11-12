package com.linguaflow.learning.practice;

/**
 * How a practice session was set up (PRC-02), stored as {@code practice_sessions.config}.
 * With the seed, the material selection (PRC-01) can be reproduced while the material
 * and the clock agree.
 * @param length the items asked for; a session may have fewer when material runs short
 * @param newRatio MIXED only: the share of fresh material
 */
public record PracticeConfig(int length, boolean includeFlashcards, boolean includeExercises, double newRatio,
		long seed) {
}
