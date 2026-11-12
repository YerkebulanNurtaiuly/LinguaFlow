package com.linguaflow.exercise;

/** A reported exercise with its set's language, for the admins' review (EX-07). */
public record ReportedExercise(Exercise exercise, String languageCode) {
}
