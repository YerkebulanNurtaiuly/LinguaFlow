package com.linguaflow.common.error;

import java.time.Duration;
import java.util.Map;

/**
 * A business error the client may retry after a while; the handler adds
 * {@code Retry-After} in whole seconds (at least 1). Used for rate limits (AUTH-06) and
 * the daily AI limit (AI-05).
 */
public class RetryLaterException extends ApiException {

	private final Duration retryAfter;

	public RetryLaterException(ErrorCode code, String detail, Duration retryAfter) {
		this(code, detail, retryAfter, Map.of());
	}

	public RetryLaterException(ErrorCode code, String detail, Duration retryAfter, Map<String, Object> properties) {
		super(code, detail, properties);
		this.retryAfter = retryAfter;
	}

	public long retryAfterSeconds() {
		return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
	}

}
