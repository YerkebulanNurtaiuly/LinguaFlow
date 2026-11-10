package com.linguaflow.grammar;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Filtered listing of grammar topics (GRM-01), always scoped to one user and language. A
 * custom fragment instead of JpaSpecificationExecutor, so the repository still offers no
 * unscoped queries (AUTH-05).
 */
public interface GrammarTopicSearch {

	/**
	 * @param pageable page and sort; sort properties must be GrammarTopic attributes (the
	 * API layer whitelists them). The ID is added as a final tie-breaker.
	 */
	Page<GrammarTopic> search(UUID userId, String languageCode, GrammarTopicFilter filter, Pageable pageable);

}
