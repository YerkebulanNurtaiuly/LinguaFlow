package com.linguaflow.learning.practice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * What a finished practice session achieved (PRC-02), stored as
 * {@code practice_sessions.summary}. Built from the reviews and attempts made in the
 * session; for a card or exercise answered more than once, the latest answer counts.
 */
@Schema(name = "PracticeSummary")
public record PracticeSummary(@Schema(description = "Items in the session") int items,
		@Schema(description = "Items answered at least once") int answeredItems, Flashcards flashcards,
		Exercises exercises,
		@Schema(description = "Time spent answering, summed over every answer that reported it") long durationMs,
		@Schema(description = "Cards whose latest grade was AGAIN, in session order") List<UUID> missedFlashcardIds,
		@Schema(description = "Exercises whose latest answer wasn't right, in session order") List<UUID> missedExerciseIds) {

	public PracticeSummary {
		missedFlashcardIds = List.copyOf(missedFlashcardIds);
		missedExerciseIds = List.copyOf(missedExerciseIds);
	}

	@Schema(name = "PracticeSummaryFlashcards")
	public record Flashcards(int items, int answered,
			@Schema(description = "Answered cards whose latest grade wasn't AGAIN") int remembered,
			@Schema(description = "Every review in the session, repeats included") int again, int hard, int good,
			int easy) {
	}

	@Schema(name = "PracticeSummaryExercises")
	public record Exercises(int items, int answered,
			@Schema(description = "Answered exercises whose latest answer was right") int correct,
			@Schema(description = "Mean latest score, 0–1; null when none was answered") @Nullable Double averageScore) {
	}

	/** A review made in the session. */
	record Review(UUID flashcardId, String grade, @Nullable Integer durationMs, Instant reviewedAt) {
	}

	/** An exercise attempt made in the session. */
	record Attempt(UUID exerciseId, boolean correct, double score, @Nullable Integer durationMs,
			Instant attemptedAt) {
	}

	/** @param reviews and attempts oldest first */
	static PracticeSummary of(List<PracticeItem> items, List<Review> reviews, List<Attempt> attempts) {
		Map<UUID, Review> latestReview = new LinkedHashMap<>();
		int[] grades = new int[4];
		long duration = 0;
		for (Review review : reviews) {
			latestReview.put(review.flashcardId(), review);
			switch (review.grade()) {
				case "AGAIN" -> grades[0]++;
				case "HARD" -> grades[1]++;
				case "GOOD" -> grades[2]++;
				default -> grades[3]++;
			}
			duration += review.durationMs() == null ? 0 : review.durationMs();
		}
		Map<UUID, Attempt> latestAttempt = new LinkedHashMap<>();
		for (Attempt attempt : attempts) {
			latestAttempt.put(attempt.exerciseId(), attempt);
			duration += attempt.durationMs() == null ? 0 : attempt.durationMs();
		}

		int cardItems = 0;
		int cardsAnswered = 0;
		int remembered = 0;
		int exerciseItems = 0;
		int exercisesAnswered = 0;
		int correct = 0;
		double scores = 0;
		List<UUID> missedCards = new ArrayList<>();
		List<UUID> missedExercises = new ArrayList<>();
		for (PracticeItem item : items) {
			if (item.type() == PracticeItemType.FLASHCARD) {
				cardItems++;
				Review review = latestReview.get(item.flashcardId());
				if (review != null) {
					cardsAnswered++;
					if ("AGAIN".equals(review.grade())) {
						missedCards.add(item.flashcardId());
					}
					else {
						remembered++;
					}
				}
			}
			else {
				exerciseItems++;
				Attempt attempt = latestAttempt.get(item.exerciseId());
				if (attempt != null) {
					exercisesAnswered++;
					scores += attempt.score();
					if (attempt.correct()) {
						correct++;
					}
					else {
						missedExercises.add(item.exerciseId());
					}
				}
			}
		}
		Double average = exercisesAnswered == 0 ? null : Math.round(scores * 1000 / exercisesAnswered) / 1000.0;
		return new PracticeSummary(items.size(), cardsAnswered + exercisesAnswered,
				new Flashcards(cardItems, cardsAnswered, remembered, grades[0], grades[1], grades[2], grades[3]),
				new Exercises(exerciseItems, exercisesAnswered, correct, average), duration, missedCards,
				missedExercises);
	}

}
