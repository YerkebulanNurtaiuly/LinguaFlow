package com.linguaflow.vocabulary;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Scoped by owner (AUTH-05); filtered listing comes from {@link VocabularyItemSearch}. */
public interface VocabularyItemRepository extends OwnedRepository<VocabularyItem>, VocabularyItemSearch {

	/** Unique key behind {@link #findDuplicateId}; a violation means a concurrent duplicate insert. */
	String LEMMA_UNIQUE_CONSTRAINT = "vocabulary_items_lemma_uq";

	/**
	 * The item that occupies this duplicate key, if any. Mirrors the unique constraint
	 * {@code (user_id, language_code, normalized_lemma, part_of_speech) NULLS NOT DISTINCT}:
	 * a null part of speech only matches another null.
	 */
	@Query("""
			select v.id from VocabularyItem v
			where v.userId = :userId and v.languageCode = :languageCode
			  and v.normalizedLemma = :normalizedLemma
			  and ((:partOfSpeech is null and v.partOfSpeech is null) or v.partOfSpeech = :partOfSpeech)
			""")
	Optional<UUID> findDuplicateId(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("normalizedLemma") String normalizedLemma, @Param("partOfSpeech") PartOfSpeech partOfSpeech);

	/**
	 * Words that count as the same word for a new or renamed one (schema.md Q2): same
	 * normalized lemma, and the same part of speech or none on either side. Exact matches
	 * come first, then the oldest.
	 */
	@Query("""
			select v.id from VocabularyItem v
			where v.userId = :userId and v.languageCode = :languageCode
			  and v.normalizedLemma = :normalizedLemma
			  and (:partOfSpeech is null or v.partOfSpeech is null or v.partOfSpeech = :partOfSpeech)
			order by case when v.partOfSpeech = :partOfSpeech then 0 else 1 end, v.createdAt, v.id
			""")
	List<UUID> findSameWordIds(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("normalizedLemma") String normalizedLemma, @Param("partOfSpeech") PartOfSpeech partOfSpeech);

	/** Which of these IDs are the user's words in this language (for attaching to a study session). */
	@Query("""
			select v.id from VocabularyItem v
			where v.userId = :userId and v.languageCode = :languageCode and v.id in :ids
			""")
	List<UUID> findOwnedIds(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("ids") Collection<UUID> ids);

	/** The user's words in this language with any of these normalized lemmas, oldest first (VOC-04). */
	@Query("""
			select new com.linguaflow.vocabulary.ExistingLemma(v.id, v.normalizedLemma, v.partOfSpeech)
			from VocabularyItem v
			where v.userId = :userId and v.languageCode = :languageCode and v.normalizedLemma in :normalizedLemmas
			order by v.createdAt, v.id
			""")
	List<ExistingLemma> findExistingLemmas(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("normalizedLemmas") Collection<String> normalizedLemmas);

	/** The user's words in a language, most recently changed first, for AI prompts (GRM-04). */
	@Query("""
			select v.lemma from VocabularyItem v
			where v.userId = :userId and v.languageCode = :languageCode
			order by v.updatedAt desc, v.id""")
	List<String> findRecentLemmas(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			Pageable page);

	/** The user's enriched words with a translation, most recently changed first (EX-02). */
	@Query("""
			select new com.linguaflow.vocabulary.KnownWord(v.id, v.lemma, v.normalizedLemma, v.translation, v.status)
			from VocabularyItem v
			where v.userId = :userId and v.languageCode = :languageCode
			  and v.aiStatus = com.linguaflow.vocabulary.AiStatus.READY
			  and v.translation is not null and trim(v.translation) <> ''
			order by v.updatedAt desc, v.id""")
	List<KnownWord> findKnownWords(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			Pageable page);

	/** The user's words among {@code ids}, in any language (FC-02). */
	@Query("select v from VocabularyItem v where v.userId = :userId and v.id in :ids")
	List<VocabularyItem> findOwnedByIds(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);

	/** Words for the AI enrichment worker (VOC-05); system-wide, the job knows the owner. */
	@Query("select v from VocabularyItem v where v.id in :ids order by v.createdAt, v.id")
	List<VocabularyItem> findAllForEnrichmentByIdIn(@Param("ids") Collection<UUID> ids);

	@Query("select v from VocabularyItem v where v.id = :id")
	Optional<VocabularyItem> findForEnrichmentById(@Param("id") UUID id);

	/** Marks the words still waiting for AI as failed; returns how many. */
	@Modifying
	@Query("""
			update VocabularyItem v set v.aiStatus = :failed, v.version = v.version + 1
			where v.id in :ids and v.aiStatus = :pending""")
	int markFailedForEnrichment(@Param("ids") Collection<UUID> ids, @Param("pending") AiStatus pending,
			@Param("failed") AiStatus failed);

}
