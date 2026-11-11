package com.linguaflow.ai;

/**
 * An LLM call that produced no usable answer. {@link Kind#retryable()} tells the job
 * queue (AI-04) whether trying again later can help.
 */
public class LlmException extends RuntimeException {

	public enum Kind {

		/** The call took longer than the use case's timeout. */
		TIMEOUT(true),
		/** Too many requests; back off. */
		RATE_LIMITED(true),
		/** The provider is overloaded or failing (5xx). */
		UNAVAILABLE(true),
		/** No connection to the provider. */
		NETWORK(true),
		/** The API key is missing, wrong or not allowed; a configuration problem. */
		AUTHENTICATION(false),
		/** The request itself is wrong (400/404/422); retrying won't help. */
		INVALID_REQUEST(false),
		/** The model declined to answer, also after the fallback. */
		REFUSED(false),
		/** The answer hit the output limit and is incomplete. */
		TRUNCATED(false),
		/** The answer didn't fit the expected structure or rules, also after one retry (AI-02). */
		INVALID_OUTPUT(false),
		/** The user reached the daily AI limit (AI-05); queued jobs wait for the reset. */
		QUOTA_EXCEEDED(false);

		private final boolean retryable;

		Kind(boolean retryable) {
			this.retryable = retryable;
		}

		public boolean retryable() {
			return retryable;
		}

	}

	private final Kind kind;

	public LlmException(Kind kind, String message, Throwable cause) {
		super(message, cause);
		this.kind = kind;
	}

	public LlmException(Kind kind, String message) {
		this(kind, message, null);
	}

	public Kind kind() {
		return kind;
	}

}
