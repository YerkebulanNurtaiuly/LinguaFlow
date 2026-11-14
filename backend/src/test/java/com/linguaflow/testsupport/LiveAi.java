package com.linguaflow.testsupport;

import java.time.Clock;
import java.util.Map;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.linguaflow.ai.AiSettings;
import com.linguaflow.ai.AnthropicLlmClient;
import com.linguaflow.ai.StructuredLlm;
import com.linguaflow.ai.TestGateways;
import com.linguaflow.ai.cache.AiCache;
import com.linguaflow.ai.prompt.PromptLibrary;
import com.linguaflow.common.config.LinguaFlowProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The real model behind the production prompts, settings and parser, for the live AI
 * eval (QA-03). It needs {@code AI_API_KEY} in the environment; {@code AI_MODEL}
 * optionally picks another model. Every call costs money, so eval tests only run with
 * {@code -Dai.live=true}. The cache is a mock (nothing is shared) and there's no quota.
 */
public final class LiveAi implements AutoCloseable {

	private final AnthropicLlmClient client;

	private final StructuredLlm llm;

	private final PromptLibrary prompts = new PromptLibrary();

	private LiveAi(AnthropicLlmClient client, AiSettings settings) {
		this.client = client;
		this.llm = new StructuredLlm(TestGateways.unlimited(client), mock(AiCache.class), settings,
				SampleAnswers.VALIDATOR);
	}

	public static LiveAi start() {
		String key = System.getenv("AI_API_KEY");
		assertThat(key).as("AI_API_KEY in the environment").isNotBlank();
		String model = System.getenv("AI_MODEL");
		// The production defaults per use case (effort, output limit, timeout)
		LinguaFlowProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
				"linguaflow.security.jwt.secret", "x".repeat(32), "linguaflow.ai.model",
				model == null || model.isBlank() ? LinguaFlowProperties.Ai.DEFAULT_MODEL : model)))
			.bind("linguaflow", LinguaFlowProperties.class)
			.get();
		AnthropicLlmClient client = new AnthropicLlmClient(AnthropicOkHttpClient.builder()
			.apiKey(key)
			.baseUrl(LinguaFlowProperties.Ai.DEFAULT_BASE_URL)
			.maxRetries(2)
			.build(), Clock.systemUTC());
		return new LiveAi(client, new AiSettings(properties));
	}

	public StructuredLlm llm() {
		return llm;
	}

	public PromptLibrary prompts() {
		return prompts;
	}

	@Override
	public void close() {
		client.close();
	}

}
