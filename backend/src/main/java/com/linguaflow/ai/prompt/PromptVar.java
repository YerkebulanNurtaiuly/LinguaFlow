package com.linguaflow.ai.prompt;

/**
 * The variables prompt templates can use (AI-03), written {@code {{key}}} in the
 * template files. A template may only use the ones its {@link PromptTemplate} declares.
 */
public enum PromptVar {

	/** The language being learned, as an English name ("German"). Set by {@link PromptVariables#forLanguages}. */
	TARGET_LANGUAGE("targetLanguage"),
	/** The learner's explanation language, as an English name ("English"). */
	EXPLANATION_LANGUAGE("explanationLanguage"),
	/** The learner's or the material's CEFR level, e.g. "A2". */
	CEFR_LEVEL("cefrLevel"),
	/** Words the learner already knows, to reuse in examples and exercises. */
	KNOWN_VOCABULARY("knownVocabulary"),
	/** Grammar topics the content should practise or relate to. */
	FOCUS_TOPICS("focusTopics"),

	/** One word or phrase to enrich, as the learner typed it. */
	WORD("word"),
	/** Several words to enrich in one call. */
	WORDS("words"),
	/** The part of speech the learner chose, if any. */
	PART_OF_SPEECH("partOfSpeech"),
	/** The learner's own translation, if they gave one. */
	TRANSLATION_HINT("translationHint"),

	/** A grammar topic's title. */
	TOPIC_TITLE("topicTitle"),
	/** A grammar topic's description. */
	TOPIC_DESCRIPTION("topicDescription"),

	/** How many exercises to write. */
	COUNT("count"),
	/** Words the exercises should practise. */
	PRACTICE_VOCABULARY("practiceVocabulary"),
	/** Recent exercises on the same material, so new ones don't repeat them. */
	RECENT_EXERCISES("recentExercises"),

	/** The exercise format, e.g. TRANSLATION. */
	EXERCISE_TYPE("exerciseType"),
	/** The exercise's instruction to the learner. */
	INSTRUCTION("instruction"),
	/** The exercise text the learner answered. */
	EXERCISE("exercise"),
	/** The model answer. */
	REFERENCE_ANSWER("referenceAnswer"),
	/** Other answers known to be correct. */
	ACCEPTED_ANSWERS("acceptedAnswers"),
	/** What the learner answered. */
	LEARNER_ANSWER("learnerAnswer"),
	/** Errors already found in the learner's answer, e.g. by the evaluation. */
	ERRORS("errors");

	private final String key;

	PromptVar(String key) {
		this.key = key;
	}

	/** The name used in template files. */
	public String key() {
		return key;
	}

}
