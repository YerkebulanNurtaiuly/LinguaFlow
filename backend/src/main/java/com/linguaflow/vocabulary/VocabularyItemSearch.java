package com.linguaflow.vocabulary;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Filtered vocabulary listing (VOC-02), always scoped to one user and language. A custom
 * fragment instead of JpaSpecificationExecutor, so the repository still offers no
 * unscoped queries (AUTH-05).
 */
public interface VocabularyItemSearch {

	/**
	 * @param pageable page and sort; sort properties must be VocabularyItem attributes
	 * (the API layer whitelists them). The ID is added as a final tie-breaker so paging is
	 * stable.
	 */
	Page<VocabularyItem> search(UUID userId, String languageCode, VocabularyFilter filter, Pageable pageable);

}
