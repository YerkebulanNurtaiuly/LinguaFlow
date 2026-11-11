package com.linguaflow.ai;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import com.linguaflow.ai.usage.AiQuotaExceededException;
import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ErrorCode;
import com.linguaflow.common.error.RetryLaterException;

/**
 * Turns an {@link LlmException} into the API error for endpoints that call the AI while
 * the user waits, e.g. grading an answer (EX-04). Background jobs don't need this; the
 * queue handles failures itself.
 * <ul>
 * <li>Daily limit reached: 429 {@code AI_QUOTA_EXCEEDED}, with {@code Retry-After} and
 * {@code resetsAt}.</li>
 * <li>Anything else: 503 {@code AI_UNAVAILABLE}. The detail never contains the provider's
 * message.</li>
 * </ul>
 */
public final class AiErrors {

	private AiErrors() {
	}

	public static ApiException toApiException(LlmException ex, Clock clock) {
		if (ex instanceof AiQuotaExceededException quota) {
			return new RetryLaterException(ErrorCode.AI_QUOTA_EXCEEDED,
					"You've used today's AI allowance. It resets at midnight UTC.",
					Duration.between(clock.instant(), quota.resetsAt()),
					Map.of("resetsAt", quota.resetsAt().toString()));
		}
		return new ApiException(ErrorCode.AI_UNAVAILABLE,
				"The AI assistant is unavailable right now. Try again in a few minutes.");
	}

}
