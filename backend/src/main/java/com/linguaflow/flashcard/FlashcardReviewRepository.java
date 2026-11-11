package com.linguaflow.flashcard;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The review log (SRS-02), always scoped by its owner. */
public interface FlashcardReviewRepository extends OwnedRepository<FlashcardReview> {

	/** The review already stored for this submission, if any. */
	Optional<FlashcardReview> findByUserIdAndFlashcardIdAndReviewToken(UUID userId, UUID flashcardId,
			UUID reviewToken);

	/** Reviews of the user's cards in one language since {@code since}: "reviewed today". */
	@Query("""
			select count(r) from FlashcardReview r
			join Flashcard f on f.id = r.flashcardId
			join VocabularyItem v on v.id = f.vocabularyItemId
			where r.userId = :userId and f.userId = :userId and v.languageCode = :languageCode
			  and r.reviewedAt >= :since""")
	long countReviewedSince(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("since") Instant since);

	/**
	 * Cards in one language whose first review was at or after {@code since}: the new cards
	 * introduced today, counted against {@code newCardsPerDay}.
	 */
	@Query("""
			select count(f) from Flashcard f
			join VocabularyItem v on v.id = f.vocabularyItemId
			where f.userId = :userId and v.userId = :userId and v.languageCode = :languageCode
			  and exists (select 1 from FlashcardReview r where r.flashcardId = f.id and r.reviewedAt >= :since)
			  and not exists (select 1 from FlashcardReview r where r.flashcardId = f.id and r.reviewedAt < :since)""")
	long countIntroducedSince(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("since") Instant since);

}
