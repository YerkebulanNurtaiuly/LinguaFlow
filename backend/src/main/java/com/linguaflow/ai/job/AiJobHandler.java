package com.linguaflow.ai.job;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Does the work of one {@link AiJobType} (AI-04). Feature modules provide one bean per
 * type, e.g. vocabulary enrichment (VOC-05); a second handler for the same type fails at
 * startup.
 * <p>
 * {@link #handle} runs outside any transaction, so a slow LLM call never holds a
 * database connection; open short transactions for reads and writes. It may run more
 * than once for the same job (a retry, or a worker that stopped mid-way), so it must be
 * idempotent: skip items that are already done or were deleted meanwhile.
 * <p>
 * How a thrown exception is treated:
 * <ul>
 * <li>{@link com.linguaflow.ai.LlmException}: retried while
 * {@code kind().retryable()} and attempts are left, dead-lettered after the last one;
 * a non-retryable kind (e.g. {@code INVALID_OUTPUT}) fails the job at once.</li>
 * <li>{@link AiJobException}: as its {@code retryable} flag says.</li>
 * <li>Any other exception: retried like a transient error, then dead-lettered.</li>
 * </ul>
 */
public interface AiJobHandler {

	AiJobType type();

	/** Runs one attempt; the returned map (may be null) is stored as the job's result. */
	@Nullable Map<String, Object> handle(AiJob job);

	/**
	 * Called once when the job ends without success (FAILED or DEAD), after that is
	 * committed, e.g. to mark its words {@code AI_FAILED}. Exceptions are logged, not
	 * rethrown.
	 */
	default void onGiveUp(AiJob job) {
	}

}
