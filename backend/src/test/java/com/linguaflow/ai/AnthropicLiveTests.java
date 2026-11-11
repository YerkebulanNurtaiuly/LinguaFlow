package com.linguaflow.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.linguaflow.ai.LlmOptions.Effort;
import com.linguaflow.common.config.LinguaFlowProperties;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
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

	enum Gender {

		MASCULINE, FEMININE, NEUTER

	}

	record Enrichment(@NotBlank String translation, @Nullable Gender gender, @Nullable String plural,
			@Size(min = 1, max = 2) List<@Valid Example> examples, @Nullable Example idiom) {
	}

	record Example(@NotBlank String sentence, @Nullable String translation) {
	}

	/** The API accepts the schemas {@link JsonSchemas} builds: nullable enums, anyOf, nested arrays. */
	@Test
	void acceptsGeneratedSchemasThroughStructuredLlm() {
		String key = System.getenv("AI_API_KEY");
		assertThat(key).as("AI_API_KEY in the environment").isNotBlank();
		AiSettings settings = mock(AiSettings.class);
		when(settings.options(any())).thenReturn(new LlmOptions(AiUseCase.VOCABULARY_ENRICHMENT,
				LinguaFlowProperties.Ai.DEFAULT_MODEL, Effort.LOW, 2_000, Duration.ofMinutes(1)));

		try (AnthropicLlmClient client = new AnthropicLlmClient(AnthropicOkHttpClient.builder()
			.apiKey(key)
			.baseUrl(LinguaFlowProperties.Ai.DEFAULT_BASE_URL)
			.maxRetries(1)
			.build(), Clock.systemUTC())) {
			StructuredLlm llm = new StructuredLlm(client, settings,
					Validation.buildDefaultValidatorFactory().getValidator());
			StructuredResponse<Enrichment> response = llm.complete(AiUseCase.VOCABULARY_ENRICHMENT,
					new LlmPrompt("You describe German nouns for English-speaking learners. Give one example.",
							"Hund"),
					Enrichment.class);

			System.out.printf("Live structured call: %d attempt(s), %d input, %d output tokens, value=%s%n",
					response.attempts(), response.inputTokens(), response.outputTokens(), response.value());
			assertThat(response.value().translation().toLowerCase()).contains("dog");
			assertThat(response.value().gender()).isEqualTo(Gender.MASCULINE);
			assertThat(response.value().examples()).isNotEmpty();
		}
	}

}
