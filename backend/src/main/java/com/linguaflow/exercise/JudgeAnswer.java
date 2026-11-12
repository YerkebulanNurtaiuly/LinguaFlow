package com.linguaflow.exercise;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** The AI judge's answer (EX-04), sent as the JSON schema of the call. */
record JudgeAnswer(
		@JsonPropertyDescription("True only when the answer has no errors") @NotNull Boolean correct,
		@JsonPropertyDescription("From 0 to 1; 1 for a fully correct answer") @NotNull @DecimalMin("0") @DecimalMax("1") Double score,
		@JsonPropertyDescription("Each error once; empty when the answer is correct") @NotNull @Size(
				max = 10) List<@NotNull @Valid Error> errors,
		@JsonPropertyDescription("The learner's answer with its errors fixed, staying as close to it as possible; null when it is correct") @Nullable @Size(
				max = 1000) String correctedAnswer,
		@JsonPropertyDescription("One or two encouraging, specific sentences in the explanation language") @NotBlank @Size(
				max = 1000) String feedback) {

	JudgeAnswer {
		errors = errors == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(errors));
	}

	record Error(
			@JsonPropertyDescription("WRONG_CASE, WRONG_ARTICLE (or gender), WRONG_ENDING, VERB_FORM, TENSE (or mood), WORD_ORDER, PREPOSITION, WRONG_WORD, SPELLING, MISSING_WORD, EXTRA_WORD, MEANING (doesn't say what was asked) or OTHER") @NotNull ErrorType type,
			@JsonPropertyDescription("The wrong part of the learner's answer, copied exactly; null when something is missing") @Nullable @Size(
					max = 300) String span,
			@JsonPropertyDescription("What is wrong and why, briefly, in the explanation language") @NotBlank @Size(
					max = 500) String explanation,
			@JsonPropertyDescription("The listed grammar topic this error belongs to, written exactly as listed without its description, or null") @Nullable @Size(
					max = 200) String topic) {
	}

}
