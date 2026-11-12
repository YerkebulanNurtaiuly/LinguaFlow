package com.linguaflow.exercise;

import java.util.List;
import java.util.Map;

import com.linguaflow.common.domain.ExerciseType;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExerciseFormatTests {

	@ParameterizedTest
	@EnumSource(ExerciseType.class)
	void everyTypeHasAPromptAndSolutionSchema(ExerciseType type) {
		ExerciseFormat format = ExerciseFormat.of(type);
		assertThat(format.type()).isEqualTo(type);
		assertThat(format.promptSchema()).containsEntry("type", "object").containsKey("properties");
		assertThat(format.solutionSchema()).containsEntry("type", "object").containsKey("properties");
		assertThat(format.promptSchema().get("required")).asInstanceOf(InstanceOfAssertFactories.LIST)
			.contains("instruction");
		assertThat(format.solutionSchema().get("required")).asInstanceOf(InstanceOfAssertFactories.LIST)
			.contains("explanation");
	}

	@Test
	@SuppressWarnings("unchecked")
	void theWordOrderSolutionSchemaNestsTokenLists() {
		Map<String, Object> schema = ExerciseFormat.of(ExerciseType.WORD_ORDER).solutionSchema();
		Map<String, Object> alternatives = (Map<String, Object>) ((Map<String, Object>) schema.get("properties"))
			.get("alternatives");
		assertThat(alternatives).containsEntry("type", "array");
		assertThat(alternatives.get("items")).isEqualTo(Map.of("type", "array", "items", Map.of("type", "string")));
	}

	@ParameterizedTest
	@EnumSource(ExerciseType.class)
	void contentRoundTripsThroughJson(ExerciseType type) {
		ExerciseContent content = ExerciseSamples.of(type);
		assertThat(ExerciseContent.fromJson(type, content.promptJson(), content.solutionJson())).isEqualTo(content);
	}

	@Test
	void storedJsonUsesTheRecordFieldNames() {
		ExerciseContent content = ExerciseSamples.of(ExerciseType.MULTIPLE_CHOICE);
		assertThat(content.promptJson()).containsEntry("question", "Ich helfe ___ Frau.")
			.containsEntry("options", List.of("die", "der", "den"));
		assertThat(content.solutionJson()).containsEntry("correctIndex", 1);
	}

	@Test
	void unknownStoredFieldsAreIgnored() {
		ExerciseContent read = ExerciseContent.fromJson(ExerciseType.ERROR_CORRECTION,
				Map.of("sentence", "Ich danke dich.", "legacy", true),
				Map.of("answer", "Ich danke dir.", "alternatives", List.of()));
		assertThat(read.prompt()).isEqualTo(new ExercisePrompt.ErrorCorrection(null, "Ich danke dich."));
	}

	@Test
	void aTypeOnlyTakesItsOwnRecords() {
		ExerciseContent fill = ExerciseSamples.of(ExerciseType.FILL_BLANK);
		assertThatThrownBy(() -> new ExerciseContent(ExerciseType.MULTIPLE_CHOICE, fill.prompt(), fill.solution()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("MULTIPLE_CHOICE takes a MultipleChoice and a Choice");
		// Text answers are shared, prompts aren't
		ExerciseContent translation = ExerciseSamples.of(ExerciseType.TRANSLATION);
		assertThatThrownBy(
				() -> new ExerciseContent(ExerciseType.TRANSFORMATION, translation.prompt(), translation.solution()))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
