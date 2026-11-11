package com.linguaflow.flashcard;

import java.util.List;
import java.util.UUID;

import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.persistence.OwnedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Flashcards. The generator's system-wide queries say so in their names. */
public interface FlashcardRepository extends OwnedRepository<Flashcard> {

	/** The user's cards of one word. */
	List<Flashcard> findAllByUserIdAndVocabularyItemId(UUID userId, UUID vocabularyItemId);

	@Query("select f.direction from Flashcard f where f.vocabularyItemId = :itemId")
	List<CardDirection> findDirectionsForGenerationByVocabularyItemId(@Param("itemId") UUID itemId);

	/** A READY word that lacks one of its two cards. */
	interface MissingCards {

		UUID getItemId();

		UUID getUserId();

	}

	/**
	 * READY words that don't have both cards yet: words from before FC-01, the dev seed, or
	 * a card creation that failed. For the startup reconciliation.
	 */
	@Query(value = """
			select v.id as itemId, v.user_id as userId from vocabulary_items v
			where v.ai_status = 'READY'
			  and (select count(*) from flashcards f where f.vocabulary_item_id = v.id) < 2
			order by v.created_at, v.id
			limit :limit""", nativeQuery = true)
	List<MissingCards> findWordsMissingCardsForGeneration(@Param("limit") int limit);

}
