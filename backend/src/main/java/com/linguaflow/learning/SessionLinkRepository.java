package com.linguaflow.learning;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The material linked to study sessions (session_vocabulary and session_grammar). The
 * links aren't owned entities: callers check that the session is the user's first, and
 * only link material of that user and language.
 */
public interface SessionLinkRepository extends Repository<SessionVocabulary, SessionVocabulary.Key> {

	@Query("""
			select sv.id.vocabularyItemId from SessionVocabulary sv
			where sv.id.studySessionId = :sessionId order by sv.addedAt, sv.id.vocabularyItemId
			""")
	List<UUID> vocabularyIds(@Param("sessionId") UUID sessionId);

	@Query("""
			select sg.id.grammarTopicId from SessionGrammar sg
			where sg.id.studySessionId = :sessionId order by sg.addedAt, sg.id.grammarTopicId
			""")
	List<UUID> grammarTopicIds(@Param("sessionId") UUID sessionId);

	@Query("""
			select new com.linguaflow.learning.SessionLinkCount(sv.id.studySessionId, count(sv))
			from SessionVocabulary sv where sv.id.studySessionId in :sessionIds group by sv.id.studySessionId
			""")
	List<SessionLinkCount> vocabularyCounts(@Param("sessionIds") Collection<UUID> sessionIds);

	@Query("""
			select new com.linguaflow.learning.SessionLinkCount(sg.id.studySessionId, count(sg))
			from SessionGrammar sg where sg.id.studySessionId in :sessionIds group by sg.id.studySessionId
			""")
	List<SessionLinkCount> grammarCounts(@Param("sessionIds") Collection<UUID> sessionIds);

	@Modifying
	@Query("delete from SessionVocabulary sv where sv.id.studySessionId = :sessionId and sv.id.vocabularyItemId = :itemId")
	int unlinkVocabulary(@Param("sessionId") UUID sessionId, @Param("itemId") UUID itemId);

	@Modifying
	@Query("delete from SessionGrammar sg where sg.id.studySessionId = :sessionId and sg.id.grammarTopicId = :topicId")
	int unlinkGrammar(@Param("sessionId") UUID sessionId, @Param("topicId") UUID topicId);

}
