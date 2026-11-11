package com.linguaflow.ai;

import java.util.List;

/**
 * The model's answer was still invalid after the retry with feedback (AI-02). Kind
 * {@link LlmException.Kind#INVALID_OUTPUT}, which isn't retryable: the same prompt is
 * unlikely to do better, so the job is marked failed.
 */
public class InvalidLlmOutputException extends LlmException {

	private final List<String> problems;

	private final List<LlmResponse> calls;

	public InvalidLlmOutputException(String message, List<String> problems, List<LlmResponse> calls) {
		super(Kind.INVALID_OUTPUT, message + ": " + String.join("; ", problems));
		this.problems = List.copyOf(problems);
		this.calls = List.copyOf(calls);
	}

	/** What was wrong with the last answer. */
	public List<String> problems() {
		return problems;
	}

	/** Every call made, for the usage log. */
	public List<LlmResponse> calls() {
		return calls;
	}

}
