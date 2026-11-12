package com.linguaflow.exercise;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.linguaflow.common.domain.ExerciseType;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * What the learner answered (EX-03), stored as JSON in {@code exercise_attempts.answer}.
 * Each exercise type takes one shape ({@link #typeFor}).
 */
public sealed interface ExerciseAnswer {

	/** The answer shape {@code type} takes. */
	static Class<? extends ExerciseAnswer> typeFor(ExerciseType type) {
		return switch (type) {
			case FILL_BLANK -> Blanks.class;
			case MULTIPLE_CHOICE -> Choice.class;
			case WORD_ORDER -> Tokens.class;
			case TRANSLATION, SENTENCE_CONSTRUCTION, ERROR_CORRECTION, TRANSFORMATION -> Text.class;
		};
	}

	/** What was typed into each gap, in order. */
	@Schema(name = "BlanksAnswer")
	record Blanks(List<@Nullable String> blanks) implements ExerciseAnswer {

		public Blanks {
			blanks = copy(blanks);
		}

	}

	/** The index of the chosen option. */
	@Schema(name = "ChoiceAnswer")
	record Choice(@Nullable Integer index) implements ExerciseAnswer {
	}

	/** The prompt's tokens in the order the learner put them. */
	@Schema(name = "TokensAnswer")
	record Tokens(List<@Nullable String> tokens) implements ExerciseAnswer {

		public Tokens {
			tokens = copy(tokens);
		}

	}

	/** A written sentence. */
	@Schema(name = "TextAnswer")
	record Text(@Nullable String text) implements ExerciseAnswer {
	}

	private static <T> List<T> copy(@Nullable List<T> list) {
		return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
	}

}
