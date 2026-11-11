package com.linguaflow.ai.prompt;

import com.linguaflow.ai.AiUseCase;
import com.linguaflow.ai.LlmPrompt;

/** A template filled in for one call (AI-03), with the version to log and cache by. */
public record RenderedPrompt(PromptTemplate template, LlmPrompt prompt) {

	public AiUseCase useCase() {
		return template.useCase();
	}

	/** e.g. {@code vocabulary-enrichment/v1}. */
	public String versionTag() {
		return template.versionTag();
	}

}
