package com.linguaflow.vocabulary;

import java.util.Optional;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Scoped by owner (AUTH-05). List filters and search arrive with VOC-02. */
public interface VocabularyItemRepository extends OwnedRepository<VocabularyItem> {

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

	/** The user's words in one language. */
	Page<VocabularyItem> findAllByUserIdAndLanguageCode(UUID userId, String languageCode, Pageable pageable);

}
