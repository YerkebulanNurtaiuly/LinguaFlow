package com.linguaflow.grammar;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.common.persistence.OwnedRepository;
import org.springframework.data.domain.Pageable;
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

	/** The user's topics among {@code ids} in a language, by title (EX-02). */
	@Query("""
			select new com.linguaflow.grammar.GrammarTopicSummary(t.id, t.title, t.normalizedTitle, t.description, t.cefrLevel)
			from GrammarTopic t
			where t.userId = :userId and t.languageCode = :languageCode and t.id in :ids
			order by t.title, t.id""")
	List<GrammarTopicSummary> findSummaries(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("ids") Collection<UUID> ids);

	/** What identifies each of the user's topics in a language, for matching against the catalog. */
	@Query("""
			select new com.linguaflow.grammar.GrammarTopicKey(t.id, t.catalogTopicId, t.normalizedTitle)
			from GrammarTopic t where t.userId = :userId and t.languageCode = :languageCode
			""")
	List<GrammarTopicKey> findKeys(@Param("userId") UUID userId, @Param("languageCode") String languageCode);


	/** Titles of the user's other topics with these statuses, most recently changed first (GRM-04). */
	@Query("""
			select t.title from GrammarTopic t
			where t.userId = :userId and t.languageCode = :languageCode and t.id <> :excludedId
			  and t.status in :statuses
			order by t.updatedAt desc, t.id""")
	List<String> findTitlesByStatus(@Param("userId") UUID userId, @Param("languageCode") String languageCode,
			@Param("excludedId") UUID excludedId,
			@Param("statuses") Collection<KnowledgeStatus> statuses,
			Pageable page);

	/** A topic for the AI explanation worker (GRM-04); system-wide, the job knows the owner. */
	@Query("select t from GrammarTopic t where t.id = :id")
	Optional<GrammarTopic> findForExplanationById(@Param("id") UUID id);

}
