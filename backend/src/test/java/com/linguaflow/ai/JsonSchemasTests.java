package com.linguaflow.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JsonSchemasTests {

	enum Gender {

		MASCULINE, FEMININE, NEUTER

	}

	@JsonClassDescription("Data about one German word")
	record Word(@JsonPropertyDescription("English translation") @NotBlank @Size(max = 100) String translation,
			@Nullable Gender gender, Long frequency, @Nullable Boolean separable, List<Example> examples,
			@Nullable Set<String> synonyms, @Nullable Example featured) {
	}

	record Example(String sentence, @Nullable String translation) {
	}

	record WithPrimitive(int count) {
	}

	record WithMap(Map<String, String> data) {
	}

	record Node(String name, @Nullable Node next) {
	}

	@Test
	@SuppressWarnings("unchecked")
	void describesARecordAsAStrictObject() {
		Map<String, Object> schema = JsonSchemas.forRecord(Word.class);
		Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

		assertThat(schema).containsEntry("type", "object")
			.containsEntry("additionalProperties", false)
			.containsEntry("description", "Data about one German word");
		// Every property is required, in declaration order; nullable ones accept null
		assertThat((List<String>) schema.get("required")).containsExactly("translation", "gender", "frequency",
				"separable", "examples", "synonyms", "featured");
		assertThat(properties.get("translation"))
			.isEqualTo(Map.of("type", "string", "description", "English translation"));
		// Nullable values are anyOf [value, null]; the API refuses a type list on enums
		assertThat(properties.get("gender")).isEqualTo(Map.of("anyOf",
				List.of(Map.of("type", "string", "enum", List.of("MASCULINE", "FEMININE", "NEUTER")),
						Map.of("type", "null"))));
		assertThat(properties.get("frequency")).isEqualTo(Map.of("type", "integer"));
		assertThat(properties.get("separable"))
			.isEqualTo(Map.of("anyOf", List.of(Map.of("type", "boolean"), Map.of("type", "null"))));
		Map<String, Object> examples = (Map<String, Object>) properties.get("examples");
		assertThat(examples).containsEntry("type", "array");
		assertThat((Map<String, Object>) examples.get("items")).containsEntry("additionalProperties", false)
			.containsEntry("required", List.of("sentence", "translation"));
		assertThat((Map<String, Object>) properties.get("featured")).containsOnlyKeys("anyOf");
		assertThat((Map<String, Object>) properties.get("synonyms")).containsOnlyKeys("anyOf");
	}

	@Test
	void leavesLengthAndRangeLimitsToBeanValidation() {
		// Structured output rejects keywords like maxLength, so they stay on the record
		String json = JsonMapper.builder().build().writeValueAsString(JsonSchemas.forRecord(Word.class));
		assertThat(json).doesNotContain("maxLength", "minLength", "minimum", "maximum", "pattern", "minItems");
	}

	@Test
	void refusesTypesItCannotDescribeSafely() {
		assertThatThrownBy(() -> JsonSchemas.forRecord(WithPrimitive.class)).hasMessageContaining("use the boxed type");
		assertThatThrownBy(() -> JsonSchemas.forRecord(WithMap.class)).hasMessageContaining("isn't supported");
		assertThatThrownBy(() -> JsonSchemas.forRecord(Node.class)).hasMessageContaining("Recursive");
		assertThatThrownBy(() -> JsonSchemas.forRecord(String.class)).hasMessageContaining("not a record");
	}

	@Test
	void theFakeClientAnswersEverySchemaItBuilds() {
		Object sample = FakeLlmClient.sample(JsonSchemas.forRecord(Word.class), "value");
		Word word = JsonMapper.builder().build().convertValue(sample, Word.class);
		assertThat(word.translation()).isEqualTo("fake translation");
		assertThat(word.gender()).isEqualTo(Gender.MASCULINE);
		assertThat(word.featured()).isNotNull();
	}

}
