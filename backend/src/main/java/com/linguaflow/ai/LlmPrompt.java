package com.linguaflow.ai;

import java.util.Objects;

/**
 * The text of one LLM call.
 * @param system stable instructions (prompt templates, AI-03); kept identical between
 * calls so the provider can cache them
 * @param user the per-call content, including the learner's material as data
 */
public record LlmPrompt(String system, String user) {

	public LlmPrompt {
		Objects.requireNonNull(system, "system");
		if (user == null || user.isBlank()) {
			throw new IllegalArgumentException("user prompt must not be blank");
		}
	}

}
