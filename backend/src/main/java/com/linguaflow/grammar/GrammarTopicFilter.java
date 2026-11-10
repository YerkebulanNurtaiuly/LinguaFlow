package com.linguaflow.grammar;

import java.util.Set;
import java.util.UUID;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.KnowledgeStatus;

/**
 * Optional filters for listing grammar topics; empty means "any". Several values for one
 * field are OR-ed, different fields AND-ed.
 * @param query text in the title (compared normalized) or the description
 * @param sessionId only topics of this study session (SES-01)
 */
public record GrammarTopicFilter(String query, Set<CefrLevel> cefrLevels, Set<KnowledgeStatus> statuses,
		UUID sessionId) {

	public GrammarTopicFilter {
		query = query == null || query.isBlank() ? null : query.strip();
		cefrLevels = cefrLevels == null ? Set.of() : Set.copyOf(cefrLevels);
		statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
	}

	public GrammarTopicFilter(String query, Set<CefrLevel> cefrLevels, Set<KnowledgeStatus> statuses) {
		this(query, cefrLevels, statuses, null);
	}

	public static GrammarTopicFilter none() {
		return new GrammarTopicFilter(null, null, null, null);
	}

}
