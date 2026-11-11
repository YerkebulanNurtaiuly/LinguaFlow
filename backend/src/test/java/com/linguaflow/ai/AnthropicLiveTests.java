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
import com.linguaflow.ai.cache.AiCache;
import com.linguaflow.ai.prompt.PromptLibrary;
import com.linguaflow.ai.prompt.PromptTemplate;
import com.linguaflow.ai.prompt.PromptVar;
import com.linguaflow.ai.prompt.PromptVariables;
import com.linguaflow.common.config.LinguaFlowProperties;
import com.linguaflow.common.domain.ExerciseType;
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
			StructuredLlm llm = new StructuredLlm(TestGateways.unlimited(client), mock(AiCache.class), settings,
					Validation.buildDefaultValidatorFactory().getValidator());
			StructuredResponse<Enrichment> response = llm.complete(AiCaller.system(), AiUseCase.VOCABULARY_ENRICHMENT,
					new LlmPrompt("You describe German nouns for English-speaking learners. Give one example.",
							"Hund"),
					Enrichment.class, OutputCheck.none());

			System.out.printf("Live structured call: %d attempt(s), %d input, %d output tokens, value=%s%n",
					response.attempts(), response.inputTokens(), response.outputTokens(), response.value());
			assertThat(response.value().translation().toLowerCase()).contains("dog");
			assertThat(response.value().gender()).isEqualTo(Gender.MASCULINE);
			assertThat(response.value().examples()).isNotEmpty();
		}
	}

	record ExerciseSet(@Size(min = 1) List<@Valid Exercise> exercises) {
	}

	record Exercise(@NotBlank String instruction, @NotBlank String content, @NotBlank String solution,
			List<String> acceptedAnswers, @NotBlank String explanation) {
	}

	/** The library's real templates produce usable answers (AI-03). */
	@Test
	void rendersLibraryTemplatesThatTheModelFollows() {
		String key = System.getenv("AI_API_KEY");
		assertThat(key).as("AI_API_KEY in the environment").isNotBlank();
		AiSettings settings = mock(AiSettings.class);
		when(settings.options(any())).thenAnswer(call -> new LlmOptions(call.getArgument(0),
				LinguaFlowProperties.Ai.DEFAULT_MODEL, Effort.LOW, 8_000, Duration.ofMinutes(2)));
		PromptLibrary library = new PromptLibrary();

		try (AnthropicLlmClient client = new AnthropicLlmClient(AnthropicOkHttpClient.builder()
			.apiKey(key)
			.baseUrl(LinguaFlowProperties.Ai.DEFAULT_BASE_URL)
			.maxRetries(1)
			.build(), Clock.systemUTC())) {
			StructuredLlm llm = new StructuredLlm(TestGateways.unlimited(client), mock(AiCache.class), settings,
					Validation.buildDefaultValidatorFactory().getValidator());

			StructuredResponse<Enrichment> word = llm.complete(AiCaller.system(),
					library.render(PromptTemplate.VOCABULARY_ENRICHMENT,
							PromptVariables.forLanguages("de", "en")
								.set(PromptVar.WORD, "Hund")
								.set(PromptVar.CEFR_LEVEL, "A1")
								.set(PromptVar.KNOWN_VOCABULARY, List.of("Park", "laufen"))),
					Enrichment.class, OutputCheck.none());
			System.out.printf("Live enrichment: %d attempt(s), %s%n", word.attempts(), word.value());
			assertThat(word.value().translation().toLowerCase()).contains("dog");
			assertThat(word.value().gender()).isEqualTo(Gender.MASCULINE);

			StructuredResponse<ExerciseSet> exercises = llm.complete(AiCaller.system(),
					library.render(PromptTemplate.exercise(ExerciseType.FILL_BLANK),
							PromptVariables.forLanguages("de", "en")
								.set(PromptVar.COUNT, 3)
								.set(PromptVar.CEFR_LEVEL, "A2")
								.set(PromptVar.FOCUS_TOPICS, List.of("Dativ nach Präpositionen (mit, bei, von, zu)"))),
					ExerciseSet.class, (set, problems) -> {
						for (int i = 0; i < set.exercises().size(); i++) {
							if (!set.exercises().get(i).content().contains("___")) {
								problems.add("exercises[" + i + "].content", "must contain the gap ___");
							}
						}
					});
			exercises.value()
				.exercises()
				.forEach(exercise -> System.out.printf("Live exercise: %s | %s -> %s (%s) %s%n", exercise.instruction(),
						exercise.content(), exercise.solution(), exercise.acceptedAnswers(), exercise.explanation()));
			System.out.printf("Live exercises: %d attempt(s), %d input, %d output tokens%n", exercises.attempts(),
					exercises.inputTokens(), exercises.outputTokens());
			assertThat(exercises.value().exercises()).hasSize(3);
		}
	}

}
