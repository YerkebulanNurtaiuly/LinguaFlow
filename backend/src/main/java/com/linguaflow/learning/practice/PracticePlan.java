package com.linguaflow.learning.practice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.linguaflow.common.domain.CardDirection;

/**
 * How a practice session's length is split between flashcards and exercises, and how
 * they're interleaved (PRC-02). Pure code.
 */
final class PracticePlan {

	/** The most exercises a session asks for: one exercise set's limit. */
	static final int MAX_EXERCISES = 20;

	private PracticePlan() {
	}

	/** How many of each a session gets. */
	record Counts(int flashcards, int exercises) {

		int total() {
			return flashcards + exercises;
		}

	}

	/**
	 * With both kinds, a third of the session (rounded up) is exercises, since they take
	 * longer; exercises fill in when there are too few cards, and cards when exercises hit
	 * {@value #MAX_EXERCISES}. Exercises need at least one grammar topic.
	 * @param availableCards reviewable cards for the chosen words
	 */
	static Counts counts(int length, boolean includeFlashcards, int availableCards, boolean includeExercises,
			boolean hasTopics) {
		boolean exercisesPossible = includeExercises && hasTopics;
		int exercises = !exercisesPossible ? 0
				: Math.min(MAX_EXERCISES, includeFlashcards ? Math.ceilDiv(length, 3) : length);
		int flashcards = includeFlashcards ? Math.min(availableCards, length - exercises) : 0;
		if (exercisesPossible) {
			exercises = Math.min(MAX_EXERCISES, length - flashcards);
		}
		return new Counts(flashcards, exercises);
	}

	/**
	 * One card per word, in the words' order, up to {@code limit}: the card that falls due
	 * first, or for a word never reviewed, recognition (target → native) before recall.
	 */
	static List<UUID> chooseCards(List<UUID> words, List<PracticeCandidates.CardOption> options, int limit) {
		Comparator<PracticeCandidates.CardOption> preferred = Comparator
			.comparing(PracticeCandidates.CardOption::dueAt, Comparator.nullsLast(Comparator.naturalOrder()))
			.thenComparing(option -> option.direction() != CardDirection.TARGET_TO_NATIVE)
			.thenComparing(PracticeCandidates.CardOption::id);
		Map<UUID, PracticeCandidates.CardOption> best = new HashMap<>();
		for (PracticeCandidates.CardOption option : options) {
			best.merge(option.vocabularyItemId(), option,
					(a, b) -> preferred.compare(a, b) <= 0 ? a : b);
		}
		return words.stream()
			.map(best::get)
			.filter(Objects::nonNull)
			.limit(limit)
			.map(PracticeCandidates.CardOption::id)
			.toList();
	}

	/**
	 * Cards and exercises spread evenly, each in its own order: the next item comes from
	 * whichever list has used the smaller share of its items, a card on a tie. Positions
	 * start at {@code from}.
	 */
	static List<PracticeItem> interleave(List<PracticeItem> cards, List<PracticeItem> exercises, int from) {
		List<PracticeItem> items = new ArrayList<>(cards.size() + exercises.size());
		int c = 0;
		int e = 0;
		while (c < cards.size() || e < exercises.size()) {
			boolean exercise = c == cards.size()
					|| e < exercises.size() && (long) e * cards.size() < (long) c * exercises.size();
			PracticeItem next = exercise ? exercises.get(e++) : cards.get(c++);
			items.add(next.at(from + items.size()));
		}
		return items;
	}

	/**
	 * The session's items once its exercises are written: what's already answered stays
	 * first, in order, and the open cards are interleaved with the new exercises after it.
	 * Exercises already in the session aren't added again.
	 */
	static List<PracticeItem> withExercises(List<PracticeItem> items, List<UUID> exerciseIds) {
		List<PracticeItem> answered = new ArrayList<>();
		List<PracticeItem> openCards = new ArrayList<>();
		List<PracticeItem> openExercises = new ArrayList<>();
		List<UUID> present = new ArrayList<>();
		for (PracticeItem item : items) {
			present.add(item.refId());
			if (item.completed()) {
				answered.add(item.at(answered.size()));
			}
			else if (item.type() == PracticeItemType.FLASHCARD) {
				openCards.add(item);
			}
			else {
				openExercises.add(item);
			}
		}
		exerciseIds.stream()
			.filter(id -> !present.contains(id))
			.forEach(id -> openExercises.add(PracticeItem.exercise(0, id)));
		List<PracticeItem> result = new ArrayList<>(answered);
		result.addAll(interleave(openCards, openExercises, answered.size()));
		return result;
	}

}
