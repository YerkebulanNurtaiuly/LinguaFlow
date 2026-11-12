package com.linguaflow.exercise;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.linguaflow.common.domain.ExerciseType;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

/**
 * The answer records of exercise generation (EX-02): one batch record per exercise type,
 * because the JSON schema needs the concrete prompt and solution records.
 * <p>
 * Only the batch itself is validated by {@code StructuredLlm}; its items are deliberately
 * not cascaded ({@code @Valid}), so one bad exercise doesn't make the model redo the whole
 * batch. {@link ExerciseGenerator} validates each item itself and drops the bad ones.
 */
final class GeneratedExercises {

	private GeneratedExercises() {
	}

	/** One generated exercise, whatever its type. */
	sealed interface Item {

		ExercisePrompt prompt();

		ExerciseSolution solution();

		/** The learner's words it uses, as listed in the prompt. */
		List<String> words();

		/** The focus topics it practises, as listed in the prompt. */
		List<String> topics();

	}

	/** A batch of generated exercises of one type. */
	sealed interface Batch {

		List<? extends Item> exercises();

	}

	/** The batch record to ask for when generating {@code type}. */
	static Class<? extends Batch> batchType(ExerciseType type) {
		return switch (type) {
			case FILL_BLANK -> FillBlankBatch.class;
			case MULTIPLE_CHOICE -> MultipleChoiceBatch.class;
			case TRANSLATION -> TranslationBatch.class;
			case SENTENCE_CONSTRUCTION -> SentenceConstructionBatch.class;
			case ERROR_CORRECTION -> ErrorCorrectionBatch.class;
			case WORD_ORDER -> WordOrderBatch.class;
			case TRANSFORMATION -> TransformationBatch.class;
		};
	}

	private static final String EXERCISES = "The exercises, as many as requested";

	private static final String WORDS = "The learner's words this exercise uses, written exactly as listed, without the translation";

	private static final String TOPICS = "The focus topics this exercise practises, written exactly as listed";

	record FillBlankBatch(@JsonPropertyDescription(EXERCISES) @NotNull List<FillBlankItem> exercises)
			implements Batch {

		FillBlankBatch {
			exercises = copy(exercises);
		}

	}

	record FillBlankItem(@NotNull ExercisePrompt.FillBlank prompt, @NotNull ExerciseSolution.Blanks solution,
			@JsonPropertyDescription(WORDS) @NotNull List<String> words,
			@JsonPropertyDescription(TOPICS) @NotNull List<String> topics) implements Item {
	}

	record MultipleChoiceBatch(@JsonPropertyDescription(EXERCISES) @NotNull List<MultipleChoiceItem> exercises)
			implements Batch {

		MultipleChoiceBatch {
			exercises = copy(exercises);
		}

	}

	record MultipleChoiceItem(@NotNull ExercisePrompt.MultipleChoice prompt, @NotNull ExerciseSolution.Choice solution,
			@JsonPropertyDescription(WORDS) @NotNull List<String> words,
			@JsonPropertyDescription(TOPICS) @NotNull List<String> topics) implements Item {
	}

	record TranslationBatch(@JsonPropertyDescription(EXERCISES) @NotNull List<TranslationItem> exercises)
			implements Batch {

		TranslationBatch {
			exercises = copy(exercises);
		}

	}

	record TranslationItem(@NotNull ExercisePrompt.Translation prompt, @NotNull ExerciseSolution.TextAnswer solution,
			@JsonPropertyDescription(WORDS) @NotNull List<String> words,
			@JsonPropertyDescription(TOPICS) @NotNull List<String> topics) implements Item {
	}

	record SentenceConstructionBatch(
			@JsonPropertyDescription(EXERCISES) @NotNull List<SentenceConstructionItem> exercises) implements Batch {

		SentenceConstructionBatch {
			exercises = copy(exercises);
		}

	}

	record SentenceConstructionItem(@NotNull ExercisePrompt.SentenceConstruction prompt,
			@NotNull ExerciseSolution.TextAnswer solution, @JsonPropertyDescription(WORDS) @NotNull List<String> words,
			@JsonPropertyDescription(TOPICS) @NotNull List<String> topics) implements Item {
	}

	record ErrorCorrectionBatch(@JsonPropertyDescription(EXERCISES) @NotNull List<ErrorCorrectionItem> exercises)
			implements Batch {

		ErrorCorrectionBatch {
			exercises = copy(exercises);
		}

	}

	record ErrorCorrectionItem(@NotNull ExercisePrompt.ErrorCorrection prompt,
			@NotNull ExerciseSolution.TextAnswer solution, @JsonPropertyDescription(WORDS) @NotNull List<String> words,
			@JsonPropertyDescription(TOPICS) @NotNull List<String> topics) implements Item {
	}

	record WordOrderBatch(@JsonPropertyDescription(EXERCISES) @NotNull List<WordOrderItem> exercises)
			implements Batch {

		WordOrderBatch {
			exercises = copy(exercises);
		}

	}

	record WordOrderItem(@NotNull ExercisePrompt.WordOrder prompt, @NotNull ExerciseSolution.TokenOrder solution,
			@JsonPropertyDescription(WORDS) @NotNull List<String> words,
			@JsonPropertyDescription(TOPICS) @NotNull List<String> topics) implements Item {
	}

	record TransformationBatch(@JsonPropertyDescription(EXERCISES) @NotNull List<TransformationItem> exercises)
			implements Batch {

		TransformationBatch {
			exercises = copy(exercises);
		}

	}

	record TransformationItem(@NotNull ExercisePrompt.Transformation prompt,
			@NotNull ExerciseSolution.TextAnswer solution, @JsonPropertyDescription(WORDS) @NotNull List<String> words,
			@JsonPropertyDescription(TOPICS) @NotNull List<String> topics) implements Item {
	}

	/** Keeps null entries, so a null item is dropped like any other bad one. */
	private static <T> List<T> copy(@Nullable List<T> list) {
		return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
	}

}
