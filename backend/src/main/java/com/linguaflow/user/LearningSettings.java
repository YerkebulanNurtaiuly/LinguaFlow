package com.linguaflow.user;

import java.util.List;

import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.domain.ExerciseType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Per-language study preferences (USR-03), used by the review queue (SRS-02) and practice
 * sessions (PRC-02).
 */
public record LearningSettings(
		@Schema(description = "Target number of reviews per day", example = "50") int dailyReviewGoal,
		@Schema(description = "Maximum new flashcards introduced per day", example = "20") int newCardsPerDay,
		@Schema(description = "Preferred exercise formats; empty = no preference") List<ExerciseType> preferredExerciseTypes,
		@Schema(description = "Items per practice session", example = "20") int practiceSessionLength,
		@Schema(description = "Flashcard directions to review (FC-01); cards exist for both, this picks which are asked") List<CardDirection> cardDirections) {

	public LearningSettings {
		preferredExerciseTypes = List.copyOf(preferredExerciseTypes);
		cardDirections = List.copyOf(cardDirections);
	}

}
