package com.linguaflow.grammar;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What the model returns when it explains a grammar topic (GRM-04). The JSON Schema sent
 * with the call is built from it (AI-02); a shape change needs a new prompt version.
 */
@JsonClassDescription("An explanation of one grammar topic for a learner")
record ExplanationAnswer(
		@JsonPropertyDescription("Two or three sentences, in the explanation language: what the topic is and when it is used") @NotBlank @Size(max = 1200) String summary,
		@JsonPropertyDescription("The rules, most important first, in the explanation language. Each states one rule plainly; it may show the forms involved as a small table written as plain text, one row per line") @Size(min = 1, max = 8) List<@NotBlank @Size(max = 1500) String> rules,
		@JsonPropertyDescription("Everyday example sentences in the learned language that show the rules, each with a translation") @Size(min = 2, max = 8) List<@Valid Example> examples,
		@JsonPropertyDescription("Typical learner mistakes with this topic and how to avoid them, in the explanation language; may be empty") @Size(max = 5) List<@NotBlank @Size(max = 600) String> commonMistakes) {

	record Example(@JsonPropertyDescription("A sentence in the learned language") @NotBlank @Size(max = 300) String sentence,
			@JsonPropertyDescription("Its translation into the explanation language") @NotBlank @Size(max = 300) String translation) {
	}

	GrammarExplanation toExplanation() {
		return new GrammarExplanation(summary.strip(), rules.stream().map(String::strip).toList(),
				examples.stream()
					.map(example -> new GrammarExplanation.Example(example.sentence().strip(),
							example.translation().strip()))
					.toList(),
				commonMistakes.stream().map(String::strip).toList());
	}

}
