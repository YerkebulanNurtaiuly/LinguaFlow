package com.linguaflow.ai.job;

/**
 * Where an AI job stands; same values as {@code ai_jobs_status_ck}. A job waiting for a
 * retry is {@link #QUEUED} again, with a later {@code next_run_at}.
 */
public enum AiJobStatus {

	/** Waiting to run, for the first time or after a retryable failure. */
	QUEUED,
	/** Claimed by a worker. */
	RUNNING,
	/** Done; {@code result} holds what the handler returned. */
	SUCCEEDED,
	/** Ended by an error that retrying can't fix, e.g. an invalid AI answer or no handler. */
	FAILED,
	/** Dead-lettered: every attempt failed with a retryable error. */
	DEAD;

	public boolean finished() {
		return this == SUCCEEDED || this == FAILED || this == DEAD;
	}

}
