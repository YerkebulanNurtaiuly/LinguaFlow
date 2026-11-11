package com.linguaflow.ai;

import java.time.Clock;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.linguaflow.common.config.LinguaFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Chooses the {@link LlmClient}: Anthropic with an API key, the fake otherwise (AI-01). */
@Configuration(proxyBeanMethods = false)
class AiConfig {

	private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

	/** Retries of rate limits, overload and dropped connections inside one call. */
	static final int SDK_MAX_RETRIES = 2;

	@Bean
	LlmClient llmClient(LinguaFlowProperties properties, Clock clock) {
		LinguaFlowProperties.Ai ai = properties.ai();
		if (!ai.usesAnthropic()) {
			log.info("AI: using the fake LLM client (no API key set, or linguaflow.ai.provider=fake)");
			return new FakeLlmClient();
		}
		log.info("AI: using Anthropic, default model {}", ai.model());
		return new AnthropicLlmClient(
				AnthropicOkHttpClient.builder()
					.apiKey(ai.apiKey())
					// Explicit, so ANTHROPIC_BASE_URL in the environment never redirects calls
					.baseUrl(ai.baseUrl())
					.maxRetries(SDK_MAX_RETRIES)
					.build(),
				clock);
	}

}
