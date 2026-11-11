package com.linguaflow.ai;

import java.time.Duration;

/**
 * The model's answer and what it cost, for AI-05's usage log.
 * @param text the answer; JSON matching the schema when one was given
 * @param model the model that answered, which may differ from the requested one when a
 * refusal fell back to another model
 * @param cacheReadTokens input tokens served from the prompt cache
 */
public record LlmResponse(String text, String model, long inputTokens, long outputTokens, long cacheReadTokens,
		Duration latency) {
}
