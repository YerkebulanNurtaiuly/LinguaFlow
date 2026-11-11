package com.linguaflow.ai.job;

/** A handler failure that says whether retrying can help (AI-04). */
public class AiJobException extends RuntimeException {

	private final boolean retryable;

	public AiJobException(String message, boolean retryable) {
		super(message);
		this.retryable = retryable;
	}

	public boolean retryable() {
		return retryable;
	}

}
