package com.linguaflow.grammar;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read-only access to the topic catalog (GRM-03). It's global seed data, not user-owned,
 * so it's a plain Repository with no write methods.
 */
public interface GrammarTopicCatalogRepository extends Repository<GrammarTopicCatalogEntry, UUID> {

	/** The whole catalog of one language, easiest first: by level, then title. */
	@Query("""
			select c from GrammarTopicCatalogEntry c
			where c.languageCode = :languageCode
			order by c.cefrLevel, c.title, c.id
			""")
	List<GrammarTopicCatalogEntry> findAllInLanguage(@Param("languageCode") String languageCode);

	Optional<GrammarTopicCatalogEntry> findByIdAndLanguageCode(UUID id, String languageCode);

}
