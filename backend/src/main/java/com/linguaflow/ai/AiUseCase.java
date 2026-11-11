package com.linguaflow.ai;

import java.util.Locale;

/**
 * What an LLM call is for. Each use case has its own model, effort, output limit and
 * timeout (see {@link AiSettings}); AI-05 logs costs per use case.
 */
public enum AiUseCase {

	/** Translation, part of speech, gender, plural, level and examples for words (VOC-05). */
	VOCABULARY_ENRICHMENT,

	/** Summary, rules and examples for a grammar topic (GRM-04). */
	GRAMMAR_EXPLANATION,

	/** Exercises for grammar topics and words (EX-02). */
	EXERCISE_GENERATION,

	/** Grading of open-ended answers (EX-04). */
	ANSWER_EVALUATION,

	/** Why an answer was wrong (EX-06). */
	MISTAKE_EXPLANATION;

	/** The name in configuration, e.g. {@code vocabulary-enrichment}. */
	public String configKey() {
		return name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

}
