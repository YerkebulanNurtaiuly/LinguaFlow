package com.linguaflow.common.ratelimit;

import java.time.Duration;

import com.linguaflow.common.error.ErrorCode;
import com.linguaflow.common.error.RetryLaterException;

/** 429 with a Retry-After header (added by GlobalExceptionHandler). */
public class RateLimitExceededException extends RetryLaterException {

	public RateLimitExceededException(Duration retryAfter) {
		super(ErrorCode.TOO_MANY_REQUESTS, "Too many attempts. Try again in " + seconds(retryAfter) + " seconds.",
				retryAfter);
	}

	private static long seconds(Duration duration) {
		// Round up so clients never retry a moment too early
		return Math.max(1, (duration.toMillis() + 999) / 1000);
	}

}
