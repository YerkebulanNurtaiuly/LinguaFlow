package com.linguaflow.exercise;

import java.util.Map;
import java.util.Objects;

import com.linguaflow.common.domain.ExerciseType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * An exercise's type with its typed prompt and solution (EX-01). The prompt and solution
 * must be the records {@link ExerciseFormat#of} names for the type.
 */
public record ExerciseContent(ExerciseType type, ExercisePrompt prompt, ExerciseSolution solution) {

	private static final JsonMapper JSON = JsonMapper.builder()
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.build();

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	public ExerciseContent {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(prompt, "prompt");
		Objects.requireNonNull(solution, "solution");
		ExerciseFormat format = ExerciseFormat.of(type);
		if (!format.promptType().isInstance(prompt) || !format.solutionType().isInstance(solution)) {
			throw new IllegalArgumentException(type + " takes a " + format.promptType().getSimpleName() + " and a "
					+ format.solutionType().getSimpleName() + ", not a " + prompt.getClass().getSimpleName()
					+ " and a " + solution.getClass().getSimpleName());
		}
	}

	/** Reads stored JSON back into the type's records. */
	public static ExerciseContent fromJson(ExerciseType type, Map<String, Object> prompt,
			Map<String, Object> solution) {
		ExerciseFormat format = ExerciseFormat.of(type);
		return new ExerciseContent(type, JSON.convertValue(prompt, format.promptType()),
				JSON.convertValue(solution, format.solutionType()));
	}

	/**
	 * One line naming what the exercise asks, e.g. "Ich gebe ___ Mann ___ Buch." or
	 * "Ich / gebe / dem / Hund / Wasser."; for "don't repeat these" lists (EX-02).
	 */
	public String summary() {
		return switch (prompt) {
			case ExercisePrompt.FillBlank p -> p.text();
			case ExercisePrompt.MultipleChoice p -> p.question();
			case ExercisePrompt.Translation p -> p.text();
			case ExercisePrompt.SentenceConstruction p -> p.task() + " (" + String.join(", ", p.words()) + ")";
			case ExercisePrompt.ErrorCorrection p -> p.sentence();
			case ExercisePrompt.WordOrder p -> String.join(" ", ((ExerciseSolution.TokenOrder) solution).tokens());
			case ExercisePrompt.Transformation p -> p.sentence() + " (" + p.task() + ")";
		};
	}

	Map<String, Object> promptJson() {
		return JSON.convertValue(prompt, MAP);
	}

	Map<String, Object> solutionJson() {
		return JSON.convertValue(solution, MAP);
	}

}
