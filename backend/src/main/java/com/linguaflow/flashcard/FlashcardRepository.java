package com.linguaflow.flashcard;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.persistence.OwnedRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Flashcards. The generator's system-wide queries say so in their names. */
public interface FlashcardRepository extends OwnedRepository<Flashcard> {

	String SEARCH_FROM = """
			from Flashcard f
			join VocabularyItem v on v.id = f.vocabularyItemId
			join SrsState s on s.id = f.id
			where f.userId = :userId and v.userId = :userId and v.languageCode = :languageCode
			  and f.direction in :directions and s.state in :states
			  and (:suspended is null or f.suspended = :suspended)
			  and (:itemId is null or f.vocabularyItemId = :itemId)
			  and (:lemmaPattern = ''
			       or v.normalizedLemma like :lemmaPattern escape '\\'
			       or lower(v.translation) like :textPattern escape '\\')""";

	/**
	 * The user's cards of words in one language, filtered (FC-02). Empty filters are passed
	 * as all values; {@code ''} patterns mean no search. Sorting by due date needs
	 * {@code JpaSort.unsafe("s.dueAt")}.
	 */
	@Query(value = "select f " + SEARCH_FROM, countQuery = "select count(f) " + SEARCH_FROM)
	Page<Flashcard> search(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("directions") Collection<CardDirection> directions,
			@Param("states") Collection<SrsState.State> states, @Param("suspended") @Nullable Boolean suspended,
			@Param("itemId") @Nullable UUID itemId, @Param("lemmaPattern") String lemmaPattern,
			@Param("textPattern") String textPattern, Pageable pageable);

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
