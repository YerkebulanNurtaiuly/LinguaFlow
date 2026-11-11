package com.linguaflow.common.domain;

/**
 * Which way a flashcard asks (FC-01); same values as {@code flashcards_direction_ck} and
 * {@code user_languages_card_directions_ck}. Shared so learning settings (user module) and
 * flashcards can both use it.
 */
public enum CardDirection {

	/** Shows the word in the learned language; the learner recalls its meaning. */
	TARGET_TO_NATIVE,
	/** Shows the meaning; the learner recalls the word, with article for nouns. */
	NATIVE_TO_TARGET

}
