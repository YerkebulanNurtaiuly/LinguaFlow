package com.linguaflow.vocabulary;

import java.util.Set;
import java.util.UUID;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.KnowledgeStatus;

/**
 * Optional filters for listing vocabulary (VOC-02); empty or null means "any". Several
 * values for the same field are OR-ed, different fields are AND-ed.
 * @param query text found in the lemma (compared normalized, so "strasse" finds "die
 * Straße") or in the translation (case-insensitive)
 * @param sessionId only words linked to this study session
 * @param tagId only words carrying this tag
 * @param aiStatuses only words in one of these AI enrichment states, e.g. PENDING_AI to
 * see what AI is still working on (FE-VOC-03)
 */
public record VocabularyFilter(String query, Set<PartOfSpeech> partsOfSpeech, Set<CefrLevel> cefrLevels,
		Set<KnowledgeStatus> statuses, UUID sessionId, UUID tagId, Set<AiStatus> aiStatuses) {

	public VocabularyFilter {
		query = query == null || query.isBlank() ? null : query.strip();
		partsOfSpeech = partsOfSpeech == null ? Set.of() : Set.copyOf(partsOfSpeech);
		cefrLevels = cefrLevels == null ? Set.of() : Set.copyOf(cefrLevels);
		statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
		aiStatuses = aiStatuses == null ? Set.of() : Set.copyOf(aiStatuses);
	}

	public VocabularyFilter(String query, Set<PartOfSpeech> partsOfSpeech, Set<CefrLevel> cefrLevels,
			Set<KnowledgeStatus> statuses, UUID sessionId, UUID tagId) {
		this(query, partsOfSpeech, cefrLevels, statuses, sessionId, tagId, Set.of());
	}

	public static VocabularyFilter none() {
		return new VocabularyFilter(null, null, null, null, null, null);
	}

}
