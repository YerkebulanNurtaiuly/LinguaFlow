package com.linguaflow.grammar;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Scoped by owner (AUTH-05); filtered listing comes from {@link GrammarTopicSearch}. */
public interface GrammarTopicRepository extends OwnedRepository<GrammarTopic>, GrammarTopicSearch {

	/** Unique key behind {@link #findDuplicateId}; a violation means a concurrent duplicate. */
	String TITLE_UNIQUE_CONSTRAINT = "grammar_topics_title_uq";

	@Query("""
			select t.id from GrammarTopic t
			where t.userId = :userId and t.languageCode = :languageCode and t.normalizedTitle = :normalizedTitle
			""")
	Optional<UUID> findDuplicateId(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("normalizedTitle") String normalizedTitle);

	/** Which of these IDs are the user's topics in this language (for attaching to a study session). */
	@Query("""
			select t.id from GrammarTopic t
			where t.userId = :userId and t.languageCode = :languageCode and t.id in :ids
			""")
	List<UUID> findOwnedIds(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("ids") Collection<UUID> ids);

	/** What identifies each of the user's topics in a language, for matching against the catalog. */
	@Query("""
			select new com.linguaflow.grammar.GrammarTopicKey(t.id, t.catalogTopicId, t.normalizedTitle)
			from GrammarTopic t where t.userId = :userId and t.languageCode = :languageCode
			""")
	List<GrammarTopicKey> findKeys(@Param("userId") UUID userId, @Param("languageCode") String languageCode);

}
