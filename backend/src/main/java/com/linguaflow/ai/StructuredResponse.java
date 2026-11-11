package com.linguaflow.ai;

import java.util.List;

/**
 * A validated AI answer (AI-02), with every call it took: one, or two when the first
 * answer was invalid. The calls carry token counts for the usage log (AI-07).
 */
public record StructuredResponse<T>(T value, List<LlmResponse> calls) {

	public StructuredResponse {
		calls = List.copyOf(calls);
	}

	public int attempts() {
		return calls.size();
	}

	public long inputTokens() {
		return calls.stream().mapToLong(LlmResponse::inputTokens).sum();
	}

	public long outputTokens() {
		return calls.stream().mapToLong(LlmResponse::outputTokens).sum();
	}

}
