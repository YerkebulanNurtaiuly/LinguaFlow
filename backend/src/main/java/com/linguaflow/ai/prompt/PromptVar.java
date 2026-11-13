package com.linguaflow.ai.prompt;

/**
 * The variables prompt templates can use (AI-03), written {@code {{key}}} in the
 * template files. A template may only use the ones its {@link PromptTemplate} declares.
 * <p>
 * Each has a shape and limits for prompt safety (AI-07): {@link PromptVariables} cuts
 * longer values and lists, and joins a single-line value's lines.
 */
public enum PromptVar {

	/** The language being learned, as an English name ("German"). Set by {@link PromptVariables#forLanguages}. */
	TARGET_LANGUAGE("targetLanguage", Shape.TRUSTED, 40, 0),
	/** The learner's explanation language, as an English name ("English"). */
	EXPLANATION_LANGUAGE("explanationLanguage", Shape.TRUSTED, 40, 0),
	/** The learner's or the material's CEFR level, e.g. "A2". */
	CEFR_LEVEL("cefrLevel", Shape.LINE, 10, 0),
	/** Words the learner already knows, to reuse in examples and exercises. */
	KNOWN_VOCABULARY("knownVocabulary", Shape.LIST, 100, 200),
	/** Grammar topics the content should practise or relate to. */
	FOCUS_TOPICS("focusTopics", Shape.LIST, 200, 20),
	/** The learner's other grammar topics, which an answer's errors may relate to (EX-04). */
	LEARNER_TOPICS("learnerTopics", Shape.LIST, 200, 30),

	/** One word or phrase to enrich, as the learner typed it. */
	WORD("word", Shape.LINE, 200, 0),
	/** Several words to enrich in one call. */
	WORDS("words", Shape.LIST, 200, 50),
	/** The part of speech the learner chose, if any. */
	PART_OF_SPEECH("partOfSpeech", Shape.LINE, 30, 0),
	/** The learner's own translation, if they gave one. */
	TRANSLATION_HINT("translationHint", Shape.LINE, 300, 0),

	/** A grammar topic's title. */
	TOPIC_TITLE("topicTitle", Shape.LINE, 200, 0),
	/** A grammar topic's description. */
	TOPIC_DESCRIPTION("topicDescription", Shape.TEXT, 2_000, 0),

	/** How many exercises to write. */
	COUNT("count", Shape.LINE, 5, 0),
	/** Words the exercises should practise. */
	PRACTICE_VOCABULARY("practiceVocabulary", Shape.LIST, 200, 50),
	/** Recent exercises on the same material, so new ones don't repeat them. */
	RECENT_EXERCISES("recentExercises", Shape.LIST, 500, 30),
	/** How hard the exercises should be, e.g. "easy: short, simple sentences" (EX-02). */
	DIFFICULTY("difficulty", Shape.LINE, 200, 0),
	/** Set (to anything) for combined exercises: each practises several topics at once (PRC-03). */
	COMBINE("combine", Shape.LINE, 10, 0),
	/** Practice words grouped by lesson, one "Lesson: word, word" line each (PRC-03). */
	LESSON_VOCABULARY("lessonVocabulary", Shape.LIST, 500, 20),
	/** The learner's frequent mistakes the exercises should test, one per line (PRG-06). */
	FOCUS_MISTAKES("focusMistakes", Shape.LIST, 400, 10),

	/** The exercise format, e.g. TRANSLATION. */
	EXERCISE_TYPE("exerciseType", Shape.LINE, 40, 0),
	/** The exercise's instruction to the learner. */
	INSTRUCTION("instruction", Shape.TEXT, 1_000, 0),
	/** The exercise text the learner answered. */
	EXERCISE("exercise", Shape.TEXT, 2_000, 0),
	/** The model answer. */
	REFERENCE_ANSWER("referenceAnswer", Shape.TEXT, 2_000, 0),
	/** Other answers known to be correct. */
	ACCEPTED_ANSWERS("acceptedAnswers", Shape.LIST, 500, 20),
	/** What the learner answered. */
	LEARNER_ANSWER("learnerAnswer", Shape.TEXT, 2_000, 0),
	/** Errors already found in the learner's answer, e.g. by the evaluation. */
	ERRORS("errors", Shape.LIST, 300, 20);

	/** How a value is written into a prompt. */
	public enum Shape {

		/** Set by the app itself (language names); inserted as is. */
		TRUSTED,
		/** One line: line breaks and runs of whitespace become single spaces. */
		LINE,
		/** Free text: line breaks stay, at most one blank line in a row. */
		TEXT,
		/** A list, one {@code - item} line per element, each like {@link #LINE}. */
		LIST

	}

	private final String key;

	private final Shape shape;

	private final int maxLength;

	private final int maxItems;

	PromptVar(String key, Shape shape, int maxLength, int maxItems) {
		this.key = key;
		this.shape = shape;
		this.maxLength = maxLength;
		this.maxItems = maxItems;
	}

	/** The name used in template files. */
	public String key() {
		return key;
	}

	public Shape shape() {
		return shape;
	}

	/** The longest value, or list item, in code points. */
	public int maxLength() {
		return maxLength;
	}

	/** For lists: how many items are kept. */
	public int maxItems() {
		return maxItems;
	}

}
