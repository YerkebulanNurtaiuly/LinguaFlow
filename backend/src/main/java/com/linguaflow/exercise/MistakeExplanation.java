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
 * Why an answer was wrong (EX-06): the AI's answer record, and what the learner sees.
 * Everything but the example sentence is in the explanation language.
 */
public record MistakeExplanation(
		@JsonPropertyDescription("True when the learner's answer is in fact correct or differs only in a way that doesn't matter; then say so in mistake and leave the rest short") @NotNull Boolean answerIsCorrect,
		@JsonPropertyDescription("What exactly is wrong: quote the wrong part and show the correct form") @NotBlank @Size(
				max = 1000) String mistake,
		@JsonPropertyDescription("The rule behind it in one or two plain sentences, naming the grammar topic") @NotBlank @Size(
				max = 1000) String rule,
		@JsonPropertyDescription("The listed grammar topic the rule belongs to, written exactly as listed without its description, or null") @Nullable @Size(
				max = 200) String topic,
		@JsonPropertyDescription("One short new example that uses the rule correctly, or null when the answer is correct") @Nullable @Valid Example example,
		@JsonPropertyDescription("Brief notes on any further errors, most important first; often empty") @NotNull @Size(
				max = 5) List<@NotBlank @Size(max = 500) String> otherErrors) {

	public MistakeExplanation {
		otherErrors = otherErrors == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(otherErrors));
	}

	/** An example sentence in the learned language with its translation. */
	public record Example(@NotBlank @Size(max = 300) String sentence, @NotBlank @Size(max = 300) String translation) {
	}

}
