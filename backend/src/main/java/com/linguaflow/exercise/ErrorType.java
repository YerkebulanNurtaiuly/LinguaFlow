package com.linguaflow.exercise;

/**
 * What kind of error an answer has (EX-04), as the AI judge reports it and as
 * {@code mistakes.error_type} stores it (no CHECK there: the list may grow). The
 * constant descriptions reach the model through the answer schema.
 */
public enum ErrorType {

	/** A noun phrase or pronoun in the wrong case, e.g. accusative after "mit". */
	WRONG_CASE(true),
	/** The wrong article or a wrong gender. */
	WRONG_ARTICLE(true),
	/** A wrong adjective, noun or pronoun ending. */
	WRONG_ENDING(true),
	/** A wrongly conjugated verb or a wrong participle. */
	VERB_FORM(true),
	/** The wrong tense or mood. */
	TENSE(true),
	/** Words in the wrong position, e.g. the verb not second or not at the end. */
	WORD_ORDER(true),
	/** The wrong preposition. */
	PREPOSITION(true),
	/** A word that doesn't fit the meaning. */
	WRONG_WORD(false),
	/** A misspelled word, umlauts included. */
	SPELLING(false),
	/** Something required is missing. */
	MISSING_WORD(true),
	/** A word that doesn't belong. */
	EXTRA_WORD(true),
	/** The answer doesn't say what was asked, or doesn't do what the instruction says. */
	MEANING(false),
	OTHER(false);

	private final boolean grammatical;

	ErrorType(boolean grammatical) {
		this.grammatical = grammatical;
	}

	/** A grammar error, which belongs to a grammar topic; spelling or vocabulary don't. */
	public boolean grammatical() {
		return grammatical;
	}

}
