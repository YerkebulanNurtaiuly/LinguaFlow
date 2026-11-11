package com.linguaflow.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.linguaflow.ai.LlmOptions.Effort;
import com.linguaflow.common.config.LinguaFlowProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One real call to the Claude API, to check the key, the model ID and the request shape
 * end to end. Off by default because it costs money (a fraction of a cent); run it with
 * {@code -Dai.live=true} and {@code AI_API_KEY} in the environment:
 * {@code ./mvnw test -Dtest=AnthropicLiveTests -Dai.live=true}.
 */
@EnabledIfSystemProperty(named = "ai.live", matches = "true")
class AnthropicLiveTests {

	@Test
	void enrichesOneWordWithStructuredOutput() {
		String key = System.getenv("AI_API_KEY");
		assertThat(key).as("AI_API_KEY in the environment").isNotBlank();
		Map<String, Object> schema = Map.of("type", "object", "properties",
				Map.of("translation", Map.of("type", "string"), "gender",
						Map.of("type", "string", "enum", List.of("MASCULINE", "FEMININE", "NEUTER"))),
				"required", List.of("translation", "gender"), "additionalProperties", false);

		try (AnthropicLlmClient client = new AnthropicLlmClient(AnthropicOkHttpClient.builder()
			.apiKey(key)
			.baseUrl(LinguaFlowProperties.Ai.DEFAULT_BASE_URL)
			.maxRetries(1)
			.build(), Clock.systemUTC())) {
			LlmResponse response = client.complete(
					new LlmPrompt("You give the English translation and grammatical gender of German nouns.",
							"Hund"),
					schema, new LlmOptions(AiUseCase.VOCABULARY_ENRICHMENT, LinguaFlowProperties.Ai.DEFAULT_MODEL,
							Effort.LOW, 2_000, Duration.ofMinutes(1)));

			JsonNode answer = JsonMapper.builder().build().readTree(response.text());
			System.out.printf("Live call: model=%s, input=%d, output=%d tokens, %d ms, answer=%s%n", response.model(),
					response.inputTokens(), response.outputTokens(), response.latency().toMillis(), response.text());
			assertThat(answer.get("translation").asString().toLowerCase()).contains("dog");
			assertThat(answer.get("gender").asString()).isEqualTo("MASCULINE");
		}
	}

}
