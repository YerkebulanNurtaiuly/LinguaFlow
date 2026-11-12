package com.linguaflow.exercise;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.linguaflow.common.domain.CardDirection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * What the learner sees (EX-01), stored as JSON in {@code exercises.prompt}. Each exercise
 * type has one record ({@link ExerciseFormat} pairs it with its solution). Text in the
 * learned language is German; instructions and tasks are in the explanation language.
 * Every record has an optional {@code instruction}; without one the frontend shows the
 * type's default ("Fill in the blank").
 * <p>
 * The records double as JSON schemas for AI generation (EX-02), so lengths live in Bean
 * Validation and the descriptions tell the model what each field holds.
 */
public sealed interface ExercisePrompt {

	/** The marker for one gap in a {@link FillBlank} text: three or more underscores. */
	String BLANK = "___";

	@Nullable String instruction();

	/** A sentence with one or more gaps to fill in. */
	record FillBlank(
			@JsonPropertyDescription("Optional instruction in the explanation language, or null") @Nullable @Size(
					max = 300) String instruction,
			@JsonPropertyDescription("German text with each gap written as ___ (three underscores)") @NotBlank @Size(
					max = 500) String text,
			@JsonPropertyDescription("Optional hint shown with the gaps, e.g. the base form \"(der Mann)\", or null") @Nullable @Size(
					max = 200) String hint)
			implements ExercisePrompt {
	}

	/** A question with 2–6 options, exactly one of them correct. */
	record MultipleChoice(
			@JsonPropertyDescription("Optional instruction in the explanation language, or null") @Nullable @Size(
					max = 300) String instruction,
			@JsonPropertyDescription("The question or a German sentence with a ___ gap") @NotBlank @Size(
					max = 500) String question,
			@JsonPropertyDescription("2 to 6 distinct options") @NotNull @Size(min = 2,
					max = 6) List<@NotBlank @Size(max = 200) String> options)
			implements ExercisePrompt {

		public MultipleChoice {
			options = copy(options);
		}

	}

	/** A sentence to translate, into or out of the learned language. */
	record Translation(
			@JsonPropertyDescription("Optional instruction in the explanation language, or null") @Nullable @Size(
					max = 300) String instruction,
			@JsonPropertyDescription("NATIVE_TO_TARGET: text is in the explanation language, translate into German; TARGET_TO_NATIVE: the reverse") @NotNull CardDirection direction,
			@JsonPropertyDescription("The text to translate") @NotBlank @Size(max = 500) String text)
			implements ExercisePrompt {
	}

	/** A task to express in a German sentence that uses the given words. */
	record SentenceConstruction(
			@JsonPropertyDescription("Optional instruction in the explanation language, or null") @Nullable @Size(
					max = 300) String instruction,
			@JsonPropertyDescription("What the sentence should say, in the explanation language") @NotBlank @Size(
					max = 500) String task,
			@JsonPropertyDescription("1 to 8 German words the sentence must use, in their base form") @NotNull @Size(
					min = 1, max = 8) List<@NotBlank @Size(max = 100) String> words)
			implements ExercisePrompt {

		public SentenceConstruction {
			words = copy(words);
		}

	}

	/** A German sentence with a mistake to correct. */
	record ErrorCorrection(
			@JsonPropertyDescription("Optional instruction in the explanation language, or null") @Nullable @Size(
					max = 300) String instruction,
			@JsonPropertyDescription("A German sentence containing exactly one grammar mistake") @NotBlank @Size(
					max = 500) String sentence)
			implements ExercisePrompt {
	}

	/** Shuffled tokens to put in the right order. */
	record WordOrder(
			@JsonPropertyDescription("Optional instruction in the explanation language, or null") @Nullable @Size(
					max = 300) String instruction,
			@JsonPropertyDescription("The words of the solution sentence, shuffled; 2 to 20 tokens") @NotNull @Size(
					min = 2, max = 20) List<@NotBlank @Size(max = 100) String> tokens)
			implements ExercisePrompt {

		public WordOrder {
			tokens = copy(tokens);
		}

	}

	/** A German sentence to change as the task says (tense, voice, case, word order…). */
	record Transformation(
			@JsonPropertyDescription("Optional instruction in the explanation language, or null") @Nullable @Size(
					max = 300) String instruction,
			@JsonPropertyDescription("The German sentence to transform") @NotBlank @Size(max = 500) String sentence,
			@JsonPropertyDescription("The change to make, in the explanation language, e.g. \"Put it into the Perfekt\"") @NotBlank @Size(
					max = 300) String task)
			implements ExercisePrompt {
	}

	/** An unmodifiable copy that keeps null elements, so Bean Validation reports them. */
	private static <T> List<T> copy(@Nullable List<T> list) {
		return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
	}

}
