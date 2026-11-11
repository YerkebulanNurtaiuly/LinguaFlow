package com.linguaflow.ai.usage;

import java.time.Instant;

import com.linguaflow.ai.LlmException;

/**
 * The user has used today's AI allowance (AI-05). The job queue postpones the job until
 * {@link #resetsAt()} without counting the attempt; a synchronous caller answers 429
 * with {@code Retry-After} (see {@link com.linguaflow.ai.AiErrors}).
 */
public class AiQuotaExceededException extends LlmException {

	private final Instant resetsAt;

	public AiQuotaExceededException(String message, Instant resetsAt) {
		super(Kind.QUOTA_EXCEEDED, message);
		this.resetsAt = resetsAt;
	}

	public Instant resetsAt() {
		return resetsAt;
	}

}
