package com.linguaflow.learning.practice;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.linguaflow.learning.practice.PracticeSummary.Attempt;
import com.linguaflow.learning.practice.PracticeSummary.Review;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PracticeSummaryTests {

	private static final Instant AT = Instant.parse("2026-11-12T10:00:00Z");

	private static UUID id(int n) {
		return new UUID(0, n);
	}

	@Test
	void theLatestAnswerCounts() {
		List<PracticeItem> items = List.of(PracticeItem.flashcard(0, id(1)), PracticeItem.exercise(1, id(100)),
				PracticeItem.flashcard(2, id(2)), PracticeItem.flashcard(3, id(3)), PracticeItem.exercise(4, id(101)),
				PracticeItem.exercise(5, id(102)));
		List<Review> reviews = List.of(new Review(id(1), "AGAIN", 4000, AT), new Review(id(2), "GOOD", 2000, AT),
				// card 1 again, remembered this time
				new Review(id(1), "HARD", null, AT.plusSeconds(60)), new Review(id(3), "AGAIN", 1000, AT));
		List<Attempt> attempts = List.of(new Attempt(id(100), true, 1, 10_000, AT),
				new Attempt(id(101), true, 1, 5000, AT),
				// exercise 101 again, now wrong
				new Attempt(id(101), false, 0.5, 5000, AT.plusSeconds(60)));

		PracticeSummary summary = PracticeSummary.of(items, reviews, attempts);
		assertThat(summary.items()).isEqualTo(6);
		assertThat(summary.answeredItems()).isEqualTo(5);
		assertThat(summary.flashcards()).isEqualTo(new PracticeSummary.Flashcards(3, 3, 2, 2, 1, 1, 0));
		assertThat(summary.exercises()).isEqualTo(new PracticeSummary.Exercises(3, 2, 1, 0.75));
		assertThat(summary.durationMs()).isEqualTo(27_000);
		assertThat(summary.missedFlashcardIds()).containsExactly(id(3));
		assertThat(summary.missedExerciseIds()).containsExactly(id(101));
	}

	@Test
	void anUnansweredSessionHasNoScore() {
		PracticeSummary summary = PracticeSummary.of(List.of(PracticeItem.exercise(0, id(100))), List.of(),
				List.of());
		assertThat(summary.answeredItems()).isZero();
		assertThat(summary.exercises().averageScore()).isNull();
		assertThat(summary.missedExerciseIds()).isEmpty();
	}

}
