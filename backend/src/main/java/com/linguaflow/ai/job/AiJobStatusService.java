package com.linguaflow.ai.job;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.linguaflow.ai.job.AiJobDtos.Progress;
import com.linguaflow.common.error.InvalidFieldException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A learner's view of their AI jobs (AI-08), always scoped by owner. */
@Service
@Transactional(readOnly = true)
public class AiJobStatusService {

	/** How many jobs one progress request may ask about. */
	static final int MAX_PROGRESS_IDS = 100;

	private final AiJobRepository jobs;

	public AiJobStatusService(AiJobRepository jobs) {
		this.jobs = jobs;
	}

	/** The user's job, or 404 when it's missing or someone else's. */
	public AiJob get(UUID userId, UUID jobId) {
		return jobs.requireOwned(jobId, userId);
	}

	/** @param statuses empty means any; @param types empty means any */
	public Page<AiJob> list(UUID userId, Collection<AiJobStatus> statuses, Collection<AiJobType> types,
			Pageable pageable) {
		return jobs.findByUserIdAndStatusInAndTypeIn(userId,
				statuses.isEmpty() ? EnumSet.allOf(AiJobStatus.class) : statuses,
				types.isEmpty() ? EnumSet.allOf(AiJobType.class) : types, pageable);
	}

	public Progress progress(UUID userId, List<UUID> ids) {
		Set<UUID> wanted = new LinkedHashSet<>(ids);
		if (wanted.isEmpty()) {
			throw new InvalidFieldException("id", "must name at least one job");
		}
		if (wanted.size() > MAX_PROGRESS_IDS) {
			throw new InvalidFieldException("id", "must name at most " + MAX_PROGRESS_IDS + " jobs");
		}
		Map<AiJobStatus, Integer> counts = new EnumMap<>(AiJobStatus.class);
		Set<UUID> owned = new LinkedHashSet<>();
		for (AiJobRepository.JobStatus job : jobs.findStatusesByUserIdAndIdIn(userId, wanted)) {
			owned.add(job.getId());
			counts.merge(job.getStatus(), 1, Integer::sum);
		}
		int found = owned.size();
		List<UUID> notFound = new ArrayList<>(wanted.stream().filter(id -> !owned.contains(id)).toList());
		int queued = counts.getOrDefault(AiJobStatus.QUEUED, 0);
		int running = counts.getOrDefault(AiJobStatus.RUNNING, 0);
		return new Progress(found, queued, running, counts.getOrDefault(AiJobStatus.SUCCEEDED, 0),
				counts.getOrDefault(AiJobStatus.FAILED, 0), counts.getOrDefault(AiJobStatus.DEAD, 0),
				found > 0 && queued + running == 0, notFound);
	}

}
