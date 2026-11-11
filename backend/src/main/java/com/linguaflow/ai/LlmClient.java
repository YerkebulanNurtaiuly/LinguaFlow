package com.linguaflow.ai;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * One call to a language model (AI-01). The real implementation is
 * {@link AnthropicLlmClient}; tests and offline development use the deterministic
 * {@link FakeLlmClient}. {@link AiConfig} picks one from the settings.
 * <p>
 * The client only produces text. Parsing and validating it (AI-02), building prompts
 * (AI-03) and running calls in the background (AI-04) happen around it; business rules
 * never depend on the model.
 */
public interface LlmClient {

	/**
	 * @param jsonSchema when set, the answer is JSON that follows this JSON Schema (an
	 * object schema as a map, e.g. built by AI-02 from a Java record)
	 * @throws LlmException when no usable answer came back
	 */
	LlmResponse complete(LlmPrompt prompt, @Nullable Map<String, Object> jsonSchema, LlmOptions options);

	/** For logs and the usage summary, e.g. "anthropic" or "fake". */
	String provider();

}
