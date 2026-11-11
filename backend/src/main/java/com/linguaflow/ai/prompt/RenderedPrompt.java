package com.linguaflow.ai.prompt;

import java.util.Set;

import com.linguaflow.ai.AiUseCase;
import com.linguaflow.ai.LlmPrompt;

/**
 * A template filled in for one call (AI-03), with the version to log and cache by.
 * @param inputFlags what prompt safety changed or noticed in the learner input (AI-07)
 */
public record RenderedPrompt(PromptTemplate template, LlmPrompt prompt, Set<PromptInputFlag> inputFlags) {

	public RenderedPrompt {
		inputFlags = Set.copyOf(inputFlags);
	}

	public AiUseCase useCase() {
		return template.useCase();
	}

	/** e.g. {@code vocabulary-enrichment/v1}. */
	public String versionTag() {
		return template.versionTag();
	}

	/** A result from a prompt that may have been manipulated isn't shared through the AI cache. */
	public boolean suspicious() {
		return inputFlags.contains(PromptInputFlag.INJECTION_SUSPECTED);
	}

}
