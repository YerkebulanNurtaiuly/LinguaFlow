package com.linguaflow.learning.practice;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * One step of a practice session: a flashcard or an exercise.
 * @param completedAt when it was first answered in the session; null while open
 */
public record PracticeItem(int position, PracticeItemType type, @Nullable UUID flashcardId,
		@Nullable UUID exerciseId, @Nullable Instant completedAt) {

	public PracticeItem {
		if ((type == PracticeItemType.FLASHCARD) != (flashcardId != null)
				|| (type == PracticeItemType.EXERCISE) != (exerciseId != null)) {
			throw new IllegalArgumentException("A " + type + " item needs exactly its own ID");
		}
	}

	static PracticeItem flashcard(int position, UUID flashcardId) {
		return new PracticeItem(position, PracticeItemType.FLASHCARD, Objects.requireNonNull(flashcardId), null, null);
	}

	static PracticeItem exercise(int position, UUID exerciseId) {
		return new PracticeItem(position, PracticeItemType.EXERCISE, null, Objects.requireNonNull(exerciseId), null);
	}

	/** The card or exercise it shows. */
	UUID refId() {
		return Objects.requireNonNull(type == PracticeItemType.FLASHCARD ? flashcardId : exerciseId);
	}

	boolean completed() {
		return completedAt != null;
	}

	PracticeItem at(int newPosition) {
		return new PracticeItem(newPosition, type, flashcardId, exerciseId, completedAt);
	}

}
