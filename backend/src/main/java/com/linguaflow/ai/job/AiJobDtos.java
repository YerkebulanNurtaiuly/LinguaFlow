package com.linguaflow.ai.job;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.linguaflow.ai.usage.AiQuota;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** Response bodies of the AI job status endpoints (AI-08). */
public final class AiJobDtos {

	private AiJobDtos() {
	}

	/** Why a queued job isn't running yet. */
	public enum Waiting {

		/** A temporary problem; it retries at {@code nextRunAt}. */
		RETRY,
		/** The learner's daily AI allowance is used up; it continues when it resets. */
		DAILY_LIMIT

	}

	@Schema(name = "AiJob", description = "Background AI work, e.g. enriching a batch of words")
	public record JobResponse(UUID id, AiJobType type, AiJobStatus status,
			@Schema(description = "SUCCEEDED, FAILED or DEAD: the job won't change any more") boolean finished,
			int attempts, int maxAttempts,
			@Schema(description = "Only for a QUEUED job that has already been tried or postponed") @Nullable Waiting waiting,
			@Schema(description = "When a QUEUED job runs next") @Nullable Instant nextRunAt,
			@Schema(description = "What the job produced, once SUCCEEDED; its shape depends on the type") @Nullable Map<String, Object> result,
			Instant createdAt, Instant updatedAt) {

		static JobResponse from(AiJob job) {
			boolean queued = job.getStatus() == AiJobStatus.QUEUED;
			return new JobResponse(job.getId(), job.getType(), job.getStatus(), job.getStatus().finished(),
					job.getAttempts(), job.getMaxAttempts(), queued ? waiting(job) : null,
					queued ? job.getNextRunAt() : null,
					job.getStatus() == AiJobStatus.SUCCEEDED ? job.getResult() : null, job.getCreatedAt(),
					job.getUpdatedAt());
		}

		/** The raw error stays on the server; the UI only learns why the job is waiting. */
		private static @Nullable Waiting waiting(AiJob job) {
			String error = job.getLastError();
			if (error == null) {
				return null;
			}
			return error.startsWith(AiQuota.LIMIT_REACHED) ? Waiting.DAILY_LIMIT : Waiting.RETRY;
		}

	}

	@Schema(name = "AiJobProgress",
			description = "How far a set of jobs has got, e.g. the batches of one bulk add. Jobs that don't exist or "
					+ "aren't yours are listed in notFound and counted nowhere else.")
	public record Progress(int total, int queued, int running, int succeeded, int failed, int dead,
			@Schema(description = "True once every found job is SUCCEEDED, FAILED or DEAD") boolean finished,
			List<UUID> notFound) {
	}

}
