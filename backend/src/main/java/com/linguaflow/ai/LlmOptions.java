package com.linguaflow.ai;

import java.time.Duration;
import java.util.Objects;

/**
 * How one LLM call runs. Built per use case by {@link AiSettings}.
 * @param model provider model ID, e.g. {@code claude-opus-5-5}
 * @param effort how much the model reasons before answering; current Claude models take
 * effort instead of a temperature
 * @param maxTokens output limit, reasoning included
 * @param timeout for the whole call, retries included
 */
public record LlmOptions(AiUseCase useCase, String model, Effort effort, int maxTokens, Duration timeout) {

	public enum Effort {

		LOW, MEDIUM, HIGH, XHIGH, MAX

	}

	public LlmOptions {
		Objects.requireNonNull(useCase, "useCase");
		Objects.requireNonNull(effort, "effort");
		Objects.requireNonNull(timeout, "timeout");
		if (model == null || model.isBlank()) {
			throw new IllegalArgumentException("model must not be blank");
		}
		if (maxTokens < 1) {
			throw new IllegalArgumentException("maxTokens must be positive");
		}
		if (timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("timeout must be positive");
		}
	}

}
