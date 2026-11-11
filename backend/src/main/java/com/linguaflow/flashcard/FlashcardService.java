package com.linguaflow.flashcard;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.error.NotFoundException;
import com.linguaflow.user.ActiveLanguageService;
import com.linguaflow.vocabulary.LemmaNormalizer;
import com.linguaflow.vocabulary.VocabularyItem;
import com.linguaflow.vocabulary.VocabularyService;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and suspending flashcards (FC-02). Every card comes with its content, built
 * from its word ({@link FlashcardContent}), and its review state.
 */
@Service
public class FlashcardService {

	/** A card as the API shows it. */
	public record CardView(Flashcard card, SrsState srs, String lemma, FlashcardContent content) {
	}

	/** List filters; empty collections and nulls mean "any". */
	public record CardFilter(Collection<CardDirection> directions, Collection<SrsState.State> states,
			@Nullable Boolean suspended, @Nullable UUID vocabularyItemId, @Nullable String q) {

		public CardFilter {
			directions = directions == null ? List.of() : List.copyOf(directions);
			states = states == null ? List.of() : List.copyOf(states);
		}

	}

	private final FlashcardRepository cards;

	private final SrsStateRepository srsStates;

	private final FlashcardReviewRepository reviews;

	private final VocabularyService vocabulary;

	private final ActiveLanguageService activeLanguages;

	FlashcardService(FlashcardRepository cards, SrsStateRepository srsStates, FlashcardReviewRepository reviews,
			VocabularyService vocabulary, ActiveLanguageService activeLanguages) {
		this.cards = cards;
		this.srsStates = srsStates;
		this.reviews = reviews;
		this.vocabulary = vocabulary;
		this.activeLanguages = activeLanguages;
	}

	/** The active language's cards, filtered; newest first by default. */
	@Transactional(readOnly = true)
	public Page<CardView> list(UUID userId, CardFilter filter, Pageable pageable) {
		String language = activeLanguages.requireActive(userId);
		String q = filter.q() == null ? "" : filter.q().strip();
		Page<Flashcard> page = cards.search(userId, language,
				filter.directions().isEmpty() ? EnumSet.allOf(CardDirection.class) : filter.directions(),
				filter.states().isEmpty() ? EnumSet.allOf(SrsState.State.class) : filter.states(), filter.suspended(),
				filter.vocabularyItemId(), q.isEmpty() ? "" : contains(LemmaNormalizer.normalize(language, q)),
				q.isEmpty() ? "" : contains(q.toLowerCase(Locale.ROOT)), pageable);
		Map<UUID, CardView> byId = views(userId, page.getContent()).stream()
			.collect(Collectors.toMap(view -> view.card().getId(), Function.identity()));
		return page.map(card -> byId.get(card.getId()));
	}

	/** One of the user's cards, in any language; 404 when it's missing or someone else's. */
	@Transactional(readOnly = true)
	public CardView get(UUID userId, UUID id) {
		return views(userId, List.of(cards.requireOwned(id, userId))).getFirst();
	}

	/** One of the user's cards' reviews (FE-FC-02); 404 when the card is missing or someone else's. */
	@Transactional(readOnly = true)
	public Page<FlashcardReview> reviews(UUID userId, UUID id, Pageable pageable) {
		cards.requireOwned(id, userId);
		return reviews.findAllByUserIdAndFlashcardId(userId, id, pageable);
	}

	/** Leaves the card out of reviews, or brings it back. */
	@Transactional
	public CardView setSuspended(UUID userId, UUID id, boolean suspended) {
		Flashcard card = cards.requireOwned(id, userId);
		card.setSuspended(suspended);
		return views(userId, List.of(cards.saveAndFlush(card))).getFirst();
	}

	/** The cards as the API shows them, in the given order. */
	List<CardView> views(UUID userId, List<Flashcard> page) {
		if (page.isEmpty()) {
			return List.of();
		}
		Map<UUID, VocabularyItem> words = vocabulary.findOwned(userId,
				page.stream().map(Flashcard::getVocabularyItemId).collect(Collectors.toSet()));
		Map<UUID, SrsState> states = srsStates
			.findAllByUserIdAndIdIn(userId, page.stream().map(Flashcard::getId).toList())
			.stream()
			.collect(Collectors.toMap(SrsState::getId, Function.identity()));
		return page.stream().map(card -> {
			VocabularyItem word = words.get(card.getVocabularyItemId());
			SrsState srs = states.get(card.getId());
			if (word == null || srs == null) {
				// Both are created and deleted with the card; only a race with a delete gets here
				throw new NotFoundException("Resource not found");
			}
			return new CardView(card, srs, word.getLemma(), FlashcardContent.of(card.getDirection(), word));
		}).toList();
	}

	/** {@code %text%} with LIKE wildcards escaped, so "50%" matches literally. */
	private static String contains(String text) {
		return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
	}

}
