package com.linguaflow.flashcard;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.vocabulary.VocabularyItemReady;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates flashcards for words (FC-01): when a word becomes READY, it gets one card per
 * {@link CardDirection}, each with a NEW {@link SrsState}.
 * <ul>
 * <li>Cards exist for both directions; the learner's {@code cardDirections} setting only
 * decides which ones are reviewed (SRS-02), so switching it never loses history.</li>
 * <li>Creating is idempotent: existing cards are kept, and a card created concurrently by
 * another instance is a unique-key clash that's ignored.</li>
 * <li>Card content isn't stored but built from the word on read
 * ({@link FlashcardContent}), so editing a word, or the AI filling it in again (VOC-06),
 * needs nothing here.</li>
 * <li>At startup, READY words without both cards get them: words from before FC-01, the
 * dev seed, or a creation that failed after the word was saved.</li>
 * </ul>
 */
@Component
public class FlashcardGenerator {

	private static final Logger log = LoggerFactory.getLogger(FlashcardGenerator.class);

	/** How many words one reconciliation round handles. */
	static final int RECONCILE_BATCH = 500;

	private final FlashcardRepository cards;

	private final SrsStateRepository srsStates;

	private final TransactionTemplate tx;

	FlashcardGenerator(FlashcardRepository cards, SrsStateRepository srsStates,
			PlatformTransactionManager transactions) {
		this.cards = cards;
		this.srsStates = srsStates;
		this.tx = new TransactionTemplate(transactions);
	}

	/** A word became READY: give it its cards. A failure is logged; the next startup retries. */
	@EventListener
	public void onWordReady(VocabularyItemReady event) {
		try {
			createFor(event.userId(), event.vocabularyItemId());
		}
		catch (RuntimeException ex) {
			log.error("Could not create flashcards for word {}; they'll be created at the next startup",
					event.vocabularyItemId(), ex);
		}
	}

	/** @return the cards created now; empty when the word already had both */
	public List<Flashcard> createFor(UUID userId, UUID vocabularyItemId) {
		try {
			List<Flashcard> created = tx.execute(status -> {
				Set<CardDirection> missing = EnumSet.allOf(CardDirection.class);
				cards.findDirectionsForGenerationByVocabularyItemId(vocabularyItemId).forEach(missing::remove);
				List<Flashcard> result = new ArrayList<>();
				for (CardDirection direction : missing) {
					Flashcard card = cards.saveAndFlush(new Flashcard(userId, vocabularyItemId, direction));
					srsStates.save(new SrsState(card));
					result.add(card);
				}
				return result;
			});
			return created == null ? List.of() : created;
		}
		catch (DataIntegrityViolationException ex) {
			// Created at the same moment elsewhere, or the word was deleted meanwhile
			log.debug("Flashcards for word {} weren't created: {}", vocabularyItemId, ex.getMostSpecificCause().getMessage());
			return List.of();
		}
	}

	/** At startup, catch up on READY words without both cards. */
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		reconcile();
	}

	/** Gives every READY word its missing cards; returns how many cards were created. */
	public int reconcile() {
		int created = 0;
		List<FlashcardRepository.MissingCards> batch;
		int rounds = 0;
		// Bounded, in case a word keeps failing; the rest waits for the next startup
		while (rounds++ < 100 && !(batch = cards.findWordsMissingCardsForGeneration(RECONCILE_BATCH)).isEmpty()) {
			int before = created;
			for (FlashcardRepository.MissingCards word : batch) {
				created += createFor(word.getUserId(), word.getItemId()).size();
			}
			if (created == before) {
				break;
			}
		}
		if (created > 0) {
			log.info("Created {} missing flashcards for READY words", created);
		}
		return created;
	}

}
