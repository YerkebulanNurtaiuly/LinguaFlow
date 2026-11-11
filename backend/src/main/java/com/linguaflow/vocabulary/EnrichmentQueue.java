package com.linguaflow.vocabulary;

import java.util.List;
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

	/** @return the IDs of the jobs created, for the UI to follow (AI-08) */
	List<UUID> enqueue(UUID userId, List<UUID> itemIds) {
		return jobs.enqueueInBatches(AiJobType.VOCABULARY_ENRICHMENT, userId, ITEM_IDS, itemIds)
			.stream()
			.map(AiJob::getId)
			.toList();
	}

}
