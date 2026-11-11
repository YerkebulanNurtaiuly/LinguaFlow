package com.linguaflow.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.linguaflow.ai.LlmOptions.Effort;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FakeLlmClientTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final LlmOptions ENRICHMENT = new LlmOptions(AiUseCase.VOCABULARY_ENRICHMENT, "claude-opus-5-5",
			Effort.LOW, 16_000, Duration.ofMinutes(1));

	private final FakeLlmClient fake = new FakeLlmClient();

	@Test
	void answersTextPromptsDeterministically() {
		LlmPrompt prompt = new LlmPrompt("sys", "Enrich: Hund\nmore lines");
		LlmResponse first = fake.complete(prompt, null, ENRICHMENT);

		assertThat(first.text()).isEqualTo("[fake vocabulary-enrichment] Enrich: Hund");
		assertThat(fake.complete(prompt, null, ENRICHMENT)).isEqualTo(first);
		assertThat(first.model()).isEqualTo("fake-claude-opus-5-5");
		assertThat(first.inputTokens()).isPositive();
		assertThat(fake.provider()).isEqualTo("fake");
	}

	@Test
	void answersWithTheSmallestJsonThatFitsTheSchema() {
		Map<String, Object> schema = Map.of("type", "object", "properties",
				Map.of("translation", Map.of("type", "string"), "gender",
						Map.of("type", List.of("string", "null"), "enum", List.of("MASCULINE", "FEMININE", "NEUTER")),
						"level", Map.of("type", "integer", "minimum", 1), "noun", Map.of("type", "boolean"),
						"examples", Map.of("type", "array", "minItems", 2, "items",
								Map.of("type", "object", "properties", Map.of("sentence", Map.of("type", "string")),
										"required", List.of("sentence"))),
						"notes", Map.of("type", "string")),
				"required", List.of("translation", "gender", "level", "noun", "examples"));

		JsonNode answer = JSON.readTree(fake.complete(new LlmPrompt("sys", "Hund"), schema, ENRICHMENT).text());

		assertThat(answer.get("translation").asString()).isEqualTo("fake translation");
		assertThat(answer.get("gender").asString()).isEqualTo("MASCULINE");
		assertThat(answer.get("level").asLong()).isEqualTo(1);
		assertThat(answer.get("noun").asBoolean()).isFalse();
		assertThat(answer.get("examples")).hasSize(2);
		assertThat(answer.at("/examples/0/sentence").asString()).isEqualTo("fake sentence");
		// Optional properties are left out
		assertThat(answer.has("notes")).isFalse();
	}

	@Test
	void usesScriptedAnswersPerUseCase() {
		fake.respondTo(AiUseCase.VOCABULARY_ENRICHMENT, prompt -> "{\"translation\":\"dog\"}");
		LlmOptions explanation = new LlmOptions(AiUseCase.GRAMMAR_EXPLANATION, "m", Effort.MEDIUM, 100,
				Duration.ofSeconds(1));

		assertThat(fake.complete(new LlmPrompt("", "Hund"), null, ENRICHMENT).text()).isEqualTo("{\"translation\":\"dog\"}");
		assertThat(fake.complete(new LlmPrompt("", "Dativ"), null, explanation).text())
			.startsWith("[fake grammar-explanation]");
	}

	@Test
	void failsWhenToldAndRecordsEveryCall() {
		fake.failNext(new LlmException(LlmException.Kind.RATE_LIMITED, "slow down"));

		assertThatThrownBy(() -> fake.complete(new LlmPrompt("", "Hund"), null, ENRICHMENT))
			.isInstanceOfSatisfying(LlmException.class,
					ex -> assertThat(ex.kind()).isEqualTo(LlmException.Kind.RATE_LIMITED));
		fake.complete(new LlmPrompt("", "Katze"), null, ENRICHMENT);

		assertThat(fake.calls()).extracting(call -> call.prompt().user()).containsExactly("Hund", "Katze");
		fake.reset();
		assertThat(fake.calls()).isEmpty();
	}

}
