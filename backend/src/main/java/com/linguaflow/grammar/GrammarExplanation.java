package com.linguaflow.grammar;

import java.util.List;

/**
 * AI-generated explanation of a topic (GRM-04), stored as JSON in
 * {@code grammar_topics.ai_explanation}, apart from the learner's own notes. Written in
 * the learner's explanation language; examples are in the learned language.
 * @param rules one rule per entry, most important first; may show forms as plain-text
 * tables, so line breaks matter
 * @param commonMistakes typical learner mistakes and how to avoid them
 */
public record GrammarExplanation(String summary, List<String> rules, List<Example> examples,
		List<String> commonMistakes) {

	public GrammarExplanation {
		rules = rules == null ? List.of() : List.copyOf(rules);
		examples = examples == null ? List.of() : List.copyOf(examples);
		commonMistakes = commonMistakes == null ? List.of() : List.copyOf(commonMistakes);
	}

	/** An example sentence in the learned language, with its translation. */
	public record Example(String sentence, String translation) {
	}

}
