package com.linguaflow.grammar;

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

}
