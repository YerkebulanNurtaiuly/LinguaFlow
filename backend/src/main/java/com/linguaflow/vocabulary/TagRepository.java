package com.linguaflow.vocabulary;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Scoped by owner (AUTH-05); every query takes the user and, where it lists, the language. */
public interface TagRepository extends OwnedRepository<Tag> {

	/** Unique key {@code (user_id, language_code, lower(name))}; a violation means a concurrent insert or rename. */
	String NAME_UNIQUE_CONSTRAINT = "tags_name_uq";

	@Query("""
			select t from Tag t
			where t.userId = :userId and t.languageCode = :languageCode
			order by lower(t.name), t.id
			""")
	List<Tag> findAllInLanguage(@Param("userId") UUID userId, @Param("languageCode") String languageCode);

	/** The tag with this name ignoring case, matching the unique index. */
	@Query("""
			select t.id from Tag t
			where t.userId = :userId and t.languageCode = :languageCode and lower(t.name) = lower(:name)
			""")
	Optional<UUID> findIdByName(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("name") String name);

	@Query("""
			select t from Tag t
			where t.userId = :userId and t.languageCode = :languageCode and t.id in :ids
			""")
	List<Tag> findAllInLanguageByIds(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("ids") Collection<UUID> ids);

	/** How many words carry each of the user's tags in this language; unused tags are absent. */
	@Query("""
			select new com.linguaflow.vocabulary.TagUsage(t.id, count(v.id))
			from VocabularyItem v join v.tags t
			where v.userId = :userId and t.userId = :userId and t.languageCode = :languageCode
			group by t.id
			""")
	List<TagUsage> countWords(@Param("userId") UUID userId, @Param("languageCode") String languageCode);

	@Query("""
			select count(v.id) from VocabularyItem v join v.tags t
			where v.userId = :userId and t.id = :tagId
			""")
	long countWordsWithTag(@Param("userId") UUID userId, @Param("tagId") UUID tagId);

}
