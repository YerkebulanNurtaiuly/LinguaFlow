package com.linguaflow.exercise;

import java.util.Map;

import com.linguaflow.ai.JsonSchemas;
import com.linguaflow.common.domain.ExerciseType;

/**
 * The prompt and solution records of one exercise type (EX-01). The pairing is fixed:
 * {@link ExerciseContent} refuses any other combination, and the JSON schemas are what
 * AI generation (EX-02) asks the model for.
 */
public record ExerciseFormat(ExerciseType type, Class<? extends ExercisePrompt> promptType,
		Class<? extends ExerciseSolution> solutionType) {

	public static ExerciseFormat of(ExerciseType type) {
		return switch (type) {
			case FILL_BLANK -> new ExerciseFormat(type, ExercisePrompt.FillBlank.class, ExerciseSolution.Blanks.class);
			case MULTIPLE_CHOICE ->
				new ExerciseFormat(type, ExercisePrompt.MultipleChoice.class, ExerciseSolution.Choice.class);
			case TRANSLATION ->
				new ExerciseFormat(type, ExercisePrompt.Translation.class, ExerciseSolution.TextAnswer.class);
			case SENTENCE_CONSTRUCTION ->
				new ExerciseFormat(type, ExercisePrompt.SentenceConstruction.class, ExerciseSolution.TextAnswer.class);
			case ERROR_CORRECTION ->
				new ExerciseFormat(type, ExercisePrompt.ErrorCorrection.class, ExerciseSolution.TextAnswer.class);
			case WORD_ORDER ->
				new ExerciseFormat(type, ExercisePrompt.WordOrder.class, ExerciseSolution.TokenOrder.class);
			case TRANSFORMATION ->
				new ExerciseFormat(type, ExercisePrompt.Transformation.class, ExerciseSolution.TextAnswer.class);
		};
	}

	/** The JSON Schema of this type's prompt, as sent for structured output. */
	public Map<String, Object> promptSchema() {
		return JsonSchemas.forRecord(promptType);
	}

	/** The JSON Schema of this type's solution. */
	public Map<String, Object> solutionSchema() {
		return JsonSchemas.forRecord(solutionType);
	}

}
