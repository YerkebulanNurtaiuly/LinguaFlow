package com.linguaflow.exercise;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * The correct answer to an exercise (EX-01), stored as JSON in {@code exercises.solution}
 * and never sent to the learner before they answer. Answers carry the accepted
 * alternatives that evaluation (EX-03, EX-04) matches against, and an optional short
 * {@code explanation} of why the answer is right, in the explanation language.
 */
public sealed interface ExerciseSolution {

	@Nullable String explanation();

	/** One answer per gap of a fill-in-the-blank text, in order. */
	record Blanks(
			@JsonPropertyDescription("One entry per ___ gap, in the order they appear") @NotNull @Size(min = 1,
					max = 10) List<@NotNull @Valid Blank> blanks,
			@JsonPropertyDescription("Why the answer is right, in one or two sentences in the explanation language, or null") @Nullable @Size(
					max = 1000) String explanation)
			implements ExerciseSolution {

		public Blanks {
			blanks = copy(blanks);
		}

	}

	/** The answer for one gap. */
	record Blank(@JsonPropertyDescription("The word or words that fill the gap") @NotBlank @Size(
			max = 200) String answer,
			@JsonPropertyDescription("Other fillings that are just as correct; often empty") @NotNull @Size(
					max = 10) List<@NotBlank @Size(max = 200) String> alternatives) {

		public Blank {
			alternatives = copy(alternatives);
		}

	}

	/** The position of the correct option in a multiple-choice question. */
	record Choice(
			@JsonPropertyDescription("Zero-based index of the one correct option") @NotNull Integer correctIndex,
			@JsonPropertyDescription("Why the answer is right, in one or two sentences in the explanation language, or null") @Nullable @Size(
					max = 1000) String explanation)
			implements ExerciseSolution {
	}

	/**
	 * A sentence answer: translation, sentence construction, error correction and
	 * transformation. For sentence construction it's one model answer among many possible.
	 */
	record TextAnswer(@JsonPropertyDescription("The best answer, as a full sentence") @NotBlank @Size(
			max = 500) String answer,
			@JsonPropertyDescription("Other answers that are just as correct") @NotNull @Size(
					max = 10) List<@NotBlank @Size(max = 500) String> alternatives,
			@JsonPropertyDescription("Why the answer is right, in one or two sentences in the explanation language, or null") @Nullable @Size(
					max = 1000) String explanation)
			implements ExerciseSolution {

		public TextAnswer {
			alternatives = copy(alternatives);
		}

	}

	/** The tokens of a word-order exercise in the right order. */
	record TokenOrder(
			@JsonPropertyDescription("The prompt's tokens in the correct order") @NotNull @Size(min = 2,
					max = 20) List<@NotBlank String> tokens,
			@JsonPropertyDescription("Other correct orders of the same tokens, e.g. with another element first") @NotNull @Size(
					max = 5) List<@NotNull List<@NotBlank String>> alternatives,
			@JsonPropertyDescription("Why the order is right, in one or two sentences in the explanation language, or null") @Nullable @Size(
					max = 1000) String explanation)
			implements ExerciseSolution {

		public TokenOrder {
			tokens = copy(tokens);
			alternatives = copy(alternatives);
		}

	}

	/** An unmodifiable copy that keeps null elements, so Bean Validation reports them. */
	private static <T> List<T> copy(@Nullable List<T> list) {
		return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
	}

}
