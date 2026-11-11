package com.linguaflow.flashcard;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.persistence.OwnedRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;
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

	/**
	 * Cards that can be reviewed (SRS-02): the user's, of words in one language, not
	 * suspended, in a direction the learner reviews, with a translation to show.
	 */
	String REVIEWABLE_FROM = """
			from Flashcard f
			join VocabularyItem v on v.id = f.vocabularyItemId
			join SrsState s on s.id = f.id
			where f.userId = :userId and v.userId = :userId and v.languageCode = :languageCode
			  and f.suspended = false and f.direction in :directions
			  and v.translation is not null and trim(v.translation) <> ''""";

	/**
	 * Due now: review cards due before tomorrow starts in the learner's zone, and learning
	 * cards whose step is over.
	 */
	String DUE_WHERE = """
			  and ((s.state = com.linguaflow.flashcard.SrsState.State.REVIEW and s.dueAt < :tomorrow)
			       or (s.state in (com.linguaflow.flashcard.SrsState.State.LEARNING,
			                       com.linguaflow.flashcard.SrsState.State.RELEARNING) and s.dueAt <= :now))""";

	/**
	 * New cards. A word's "native → target" card waits while its "target → native" card is
	 * also reviewed and still new or seen today, so recognition comes first and a word's two
	 * cards aren't asked back to back.
	 */
	String NEW_WHERE = """
			  and s.state = com.linguaflow.flashcard.SrsState.State.NEW
			  and not exists (
			      select 1 from Flashcard f2 join SrsState s2 on s2.id = f2.id
			      where f.direction = com.linguaflow.common.domain.CardDirection.NATIVE_TO_TARGET
			        and f2.vocabularyItemId = f.vocabularyItemId
			        and f2.direction = com.linguaflow.common.domain.CardDirection.TARGET_TO_NATIVE
			        and f2.direction in :directions and f2.suspended = false
			        and (s2.state = com.linguaflow.flashcard.SrsState.State.NEW or s2.lastReviewedAt >= :today))""";

	/**
	 * Due cards in queue order: overdue (due before today) oldest first, then the rest of
	 * today's, learning steps first and then the weakest (lowest ease, most lapses).
	 */
	@Query("select f " + REVIEWABLE_FROM + DUE_WHERE + """

			order by case when s.dueAt < :today then 0 else 1 end,
			         case when s.dueAt < :today then s.dueAt end,
			         case when s.state = com.linguaflow.flashcard.SrsState.State.REVIEW then 1 else 0 end,
			         s.easeFactor, s.lapses desc, s.dueAt, f.id""")
	List<Flashcard> findDue(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("directions") Collection<CardDirection> directions, @Param("now") Instant now,
			@Param("today") Instant today, @Param("tomorrow") Instant tomorrow, Limit limit);

	@Query("select count(f) " + REVIEWABLE_FROM + DUE_WHERE)
	long countDue(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("directions") Collection<CardDirection> directions, @Param("now") Instant now,
			@Param("tomorrow") Instant tomorrow);

	/** New cards in the order the words were added, "target → native" first. */
	@Query("select f " + REVIEWABLE_FROM + NEW_WHERE + """

			order by v.createdAt, v.id,
			         case when f.direction = com.linguaflow.common.domain.CardDirection.TARGET_TO_NATIVE
			              then 0 else 1 end""")
	List<Flashcard> findNew(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("directions") Collection<CardDirection> directions, @Param("today") Instant today, Limit limit);

	@Query("select count(f) " + REVIEWABLE_FROM + NEW_WHERE)
	long countNew(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("directions") Collection<CardDirection> directions, @Param("today") Instant today);

	/** When the next learning step comes due later today, if any. */
	@Query("select min(s.dueAt) " + REVIEWABLE_FROM + """

			  and s.state in (com.linguaflow.flashcard.SrsState.State.LEARNING,
			                  com.linguaflow.flashcard.SrsState.State.RELEARNING)
			  and s.dueAt > :now and s.dueAt < :tomorrow""")
	@Nullable
	Instant findNextLearningDue(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("directions") Collection<CardDirection> directions, @Param("now") Instant now,
			@Param("tomorrow") Instant tomorrow);

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
