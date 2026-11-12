package com.linguaflow.learning.practice;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.learning.practice.PracticeCandidates.CardOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PracticePlanTests {

	private static UUID id(int n) {
		return new UUID(0, n);
	}

	@ParameterizedTest(name = "length {0}, cards {1}/{2}, exercises {3}, topics {4} → {5} cards, {6} exercises")
	@CsvSource({
			// both: a third exercises, rounded up
			"20, true, 100, true, true, 13, 7", "9, true, 100, true, true, 6, 3",
			// too few cards: exercises fill up, to at most 20
			"20, true, 4, true, true, 4, 16", "60, true, 10, true, true, 10, 20",
			// many items: exercises stop at 20, cards fill the rest
			"90, true, 100, true, true, 70, 20",
			// one kind only
			"20, true, 100, false, true, 20, 0", "20, false, 0, true, true, 0, 20", "50, false, 0, true, true, 0, 20",
			// no topics: no exercises, cards take the whole length
			"20, true, 100, true, false, 20, 0", "20, true, 3, true, false, 3, 0", "20, false, 0, true, false, 0, 0" })
	void theLengthIsSplitBetweenCardsAndExercises(int length, boolean flashcards, int available, boolean exercises,
			boolean topics, int expectedCards, int expectedExercises) {
		assertThat(PracticePlan.counts(length, flashcards, available, exercises, topics))
			.isEqualTo(new PracticePlan.Counts(expectedCards, expectedExercises));
	}

	private static List<PracticeItem> cards(int n) {
		return IntStream.range(0, n).mapToObj(i -> PracticeItem.flashcard(i, id(i))).toList();
	}

	private static List<PracticeItem> exercises(int n) {
		return IntStream.range(0, n).mapToObj(i -> PracticeItem.exercise(i, id(100 + i))).toList();
	}

	private static String pattern(List<PracticeItem> items) {
		return items.stream()
			.map(item -> item.type() == PracticeItemType.FLASHCARD ? "C" : "E")
			.collect(Collectors.joining());
	}

	@Test
	void cardsAndExercisesAreSpreadEvenly() {
		assertThat(pattern(PracticePlan.interleave(cards(6), exercises(3), 0))).isEqualTo("CECCECCEC");
		assertThat(pattern(PracticePlan.interleave(cards(2), exercises(2), 0))).isEqualTo("CECE");
		assertThat(pattern(PracticePlan.interleave(cards(1), exercises(4), 0))).isEqualTo("CEEEE");
		assertThat(pattern(PracticePlan.interleave(cards(3), List.of(), 0))).isEqualTo("CCC");
		assertThat(pattern(PracticePlan.interleave(List.of(), exercises(2), 0))).isEqualTo("EE");

		List<PracticeItem> items = PracticePlan.interleave(cards(6), exercises(3), 4);
		assertThat(items).extracting(PracticeItem::position).containsExactly(4, 5, 6, 7, 8, 9, 10, 11, 12);
		// Each list keeps its order
		assertThat(items.stream().filter(item -> item.type() == PracticeItemType.EXERCISE).map(PracticeItem::refId))
			.containsExactly(id(100), id(101), id(102));
	}

	@Test
	void exercisesJoinAfterWhatIsAlreadyAnswered() {
		Instant at = Instant.parse("2026-11-12T10:00:00Z");
		List<PracticeItem> items = List.of(new PracticeItem(0, PracticeItemType.FLASHCARD, id(0), null, at),
				PracticeItem.flashcard(1, id(1)), new PracticeItem(2, PracticeItemType.FLASHCARD, id(2), null, at),
				PracticeItem.flashcard(3, id(3)));

		List<PracticeItem> merged = PracticePlan.withExercises(items, List.of(id(100), id(101)));
		assertThat(merged).extracting(PracticeItem::position).containsExactly(0, 1, 2, 3, 4, 5);
		assertThat(merged).extracting(PracticeItem::refId)
			.containsExactly(id(0), id(2), id(1), id(100), id(3), id(101));
		assertThat(merged.get(0).completedAt()).isEqualTo(at);
		assertThat(merged.get(1).completedAt()).isEqualTo(at);

		// Adding the same exercises again changes nothing
		assertThat(PracticePlan.withExercises(merged, List.of(id(100), id(101)))).extracting(PracticeItem::refId)
			.containsExactly(id(0), id(2), id(1), id(100), id(3), id(101));
	}

	@Test
	void oneCardPerWordDueFirstElseRecognition() {
		Instant soon = Instant.parse("2026-11-12T10:00:00Z");
		Instant later = soon.plusSeconds(86_400);
		List<CardOption> options = List.of(
				// word 1: both new, so target to native
				new CardOption(id(11), id(1), CardDirection.NATIVE_TO_TARGET, null),
				new CardOption(id(12), id(1), CardDirection.TARGET_TO_NATIVE, null),
				// word 2: the one due first, even recall
				new CardOption(id(21), id(2), CardDirection.TARGET_TO_NATIVE, later),
				new CardOption(id(22), id(2), CardDirection.NATIVE_TO_TARGET, soon),
				// word 3: a reviewed card beats a new one
				new CardOption(id(31), id(3), CardDirection.TARGET_TO_NATIVE, null),
				new CardOption(id(32), id(3), CardDirection.NATIVE_TO_TARGET, later));

		// In the words' order; word 4 has no card
		assertThat(PracticePlan.chooseCards(List.of(id(3), id(4), id(1), id(2)), options, 10))
			.containsExactly(id(32), id(12), id(22));
		assertThat(PracticePlan.chooseCards(List.of(id(3), id(4), id(1), id(2)), options, 2))
			.containsExactly(id(32), id(12));
	}

}
