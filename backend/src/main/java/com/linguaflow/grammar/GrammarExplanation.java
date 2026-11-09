package com.linguaflow.grammar;

import java.util.List;

/** AI-generated explanation of a topic (GRM-04), stored as JSON apart from personal notes. */
public record GrammarExplanation(String summary, List<String> rules, List<String> examples) {

	public GrammarExplanation {
		rules = List.copyOf(rules);
		examples = List.copyOf(examples);
	}

}
