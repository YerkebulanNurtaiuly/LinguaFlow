package com.linguaflow.vocabulary;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.linguaflow.ai.job.AiJob;
import com.linguaflow.ai.job.AiJobQueue;
import com.linguaflow.ai.job.AiJobType;
import org.springframework.stereotype.Component;

/**
 * Queues new words for AI enrichment (VOC-05). Joins the caller's transaction, so a job
 * exists exactly when its words were saved. Words are batched, 20 per job by default
 * ({@code linguaflow.ai.jobs.batch-size}).
 */
@Component
class EnrichmentQueue {

	/** The job payload key holding the word IDs. */
	static final String ITEM_IDS = "vocabularyItemIds";

	private final AiJobQueue jobs;

	EnrichmentQueue(AiJobQueue jobs) {
		this.jobs = jobs;
	}

	/** The payload flag that makes the job skip the shared AI cache (VOC-06). */
	static final String FRESH = "fresh";

	/** One job for one word that is asked again, bypassing the cache (VOC-06). */
	UUID enqueueFresh(UUID userId, UUID itemId) {
		return jobs.enqueue(AiJobType.VOCABULARY_ENRICHMENT, userId,
				Map.of(ITEM_IDS, List.of(itemId.toString()), FRESH, true))
			.getId();
	}

	/** @return the IDs of the jobs created, for the UI to follow (AI-08) */
	List<UUID> enqueue(UUID userId, List<UUID> itemIds) {
		return jobs.enqueueInBatches(AiJobType.VOCABULARY_ENRICHMENT, userId, ITEM_IDS, itemIds)
			.stream()
			.map(AiJob::getId)
			.toList();
	}

}
