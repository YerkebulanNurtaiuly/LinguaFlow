package com.linguaflow.ai.job;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.linguaflow.common.config.LinguaFlowProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Puts AI work into the queue (AI-04). Enqueueing joins the caller's transaction, so a
 * job exists exactly when the data it works on was committed: if adding the words rolls
 * back, so do their jobs. The request returns at once; {@link AiJobWorker} runs the job
 * in the background, also after a restart.
 */
@Service
public class AiJobQueue {

	/** Signals the worker that new jobs were committed, so it needn't wait for the next poll. */
	public record JobsEnqueued(int count) {
	}

	private final AiJobRepository jobs;

	private final Clock clock;

	private final LinguaFlowProperties.Ai.Jobs settings;

	private final ApplicationEventPublisher events;

	public AiJobQueue(AiJobRepository jobs, Clock clock, LinguaFlowProperties properties,
			ApplicationEventPublisher events) {
		this.jobs = jobs;
		this.clock = clock;
		this.settings = properties.ai().jobs();
		this.events = events;
	}

	/** @param payload JSON-serializable data the handler needs, e.g. item IDs; never secrets */
	@Transactional
	public AiJob enqueue(AiJobType type, @Nullable UUID userId, Map<String, Object> payload) {
		AiJob job = jobs.save(new AiJob(type, userId, payload, settings.maxAttempts(), clock.instant()));
		events.publishEvent(new JobsEnqueued(1));
		return job;
	}

	/**
	 * Splits {@code ids} into jobs of at most {@code batch-size} (default 20), each with the
	 * payload {@code {key: [ids…]}}, e.g. 100 new words become 5 enrichment jobs.
	 */
	@Transactional
	public List<AiJob> enqueueInBatches(AiJobType type, @Nullable UUID userId, String key, List<UUID> ids) {
		List<AiJob> created = new ArrayList<>();
		for (int from = 0; from < ids.size(); from += settings.batchSize()) {
			List<String> batch = ids.subList(from, Math.min(ids.size(), from + settings.batchSize()))
				.stream()
				.map(UUID::toString)
				.toList();
			created.add(jobs.save(new AiJob(type, userId, Map.of(key, batch), settings.maxAttempts(), clock.instant())));
		}
		if (!created.isEmpty()) {
			events.publishEvent(new JobsEnqueued(created.size()));
		}
		return created;
	}

}
