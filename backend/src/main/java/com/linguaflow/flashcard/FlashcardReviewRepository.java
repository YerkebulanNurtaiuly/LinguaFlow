package com.linguaflow.flashcard;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The review log (SRS-02), always scoped by its owner. */
public interface FlashcardReviewRepository extends OwnedRepository<FlashcardReview> {

	/** The review already stored for this submission, if any. */
	Optional<FlashcardReview> findByUserIdAndFlashcardIdAndReviewToken(UUID userId, UUID flashcardId,
			UUID reviewToken);

	/** One card's review history (FE-FC-02). */
	Page<FlashcardReview> findAllByUserIdAndFlashcardId(UUID userId, UUID flashcardId, Pageable pageable);

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
	 * Reviews in one language since {@code since} of cards that were in {@code state}, with
	 * one of {@code grades}: retention (SRS-03).
	 */
	@Query("""
			select count(r) from FlashcardReview r
			join Flashcard f on f.id = r.flashcardId
			join VocabularyItem v on v.id = f.vocabularyItemId
			where r.userId = :userId and f.userId = :userId and v.languageCode = :languageCode
			  and r.stateBefore = :state and r.reviewedAt >= :since and r.grade in :grades""")
	long countInStateSince(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("state") SrsState.State state, @Param("since") Instant since,
			@Param("grades") Collection<ReviewGrade> grades);

	/**
	 * The learner's calendar days with a review in one language, as ISO dates, oldest
	 * first; {@code zone} is a name PostgreSQL knows (IANA or "UTC"). For streaks.
	 */
	@Query(value = """
			select distinct to_char(r.reviewed_at at time zone :zone, 'YYYY-MM-DD') as day
			from flashcard_reviews r
			join flashcards f on f.id = r.flashcard_id
			join vocabulary_items v on v.id = f.vocabulary_item_id
			where r.user_id = :userId and v.language_code = :languageCode
			order by day""", nativeQuery = true)
	List<String> findReviewDays(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("zone") String zone);

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
