package com.linguaflow.common.domain;

/**
 * Exercise formats (EX-01); same values as the {@code exercises.type} CHECK constraint.
 * Shared so learning settings (user module) and exercises can both use it.
 */
public enum ExerciseType {

	FILL_BLANK, MULTIPLE_CHOICE, TRANSLATION, SENTENCE_CONSTRUCTION, ERROR_CORRECTION, WORD_ORDER, TRANSFORMATION

}
