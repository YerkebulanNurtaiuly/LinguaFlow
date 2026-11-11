package com.linguaflow.flashcard;

/**
 * How well the learner recalled a card (SRS-01); same values as
 * {@code flashcard_reviews_grade_ck}.
 */
public enum ReviewGrade {

	/** Forgot it. */
	AGAIN,
	/** Recalled it with real effort. */
	HARD,
	/** Recalled it. */
	GOOD,
	/** Recalled it at once. */
	EASY

}
