package com.linguaflow.exercise;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The result of checking an answer (EX-03).
 * @param decided false when the rules can't tell and the answer needs the AI judge
 * (EX-04): an open answer that matches none of the known ones
 * @param correct only meaningful when decided
 * @param score 0–1 with three decimals; partial credit for gaps and word order
 * @param notes what was accepted although it differed, e.g. a missing capital letter
 * @param parts for fill-in-the-blank one entry per gap, for word order one per answer
 * token (whether it's in a correct place); empty otherwise
 * @param correctAnswer the solution as it would be shown to the learner
 * @param feedback the AI judge's findings (EX-04); null when the rules decided
 */
public record Evaluation(boolean decided, boolean correct, double score, EvaluatedBy evaluatedBy, Set<Note> notes,
		List<Boolean> parts, @Nullable String correctAnswer, @Nullable Feedback feedback) {

	/** A result of the rules, without AI feedback. */
	public Evaluation(boolean decided, boolean correct, double score, EvaluatedBy evaluatedBy, Set<Note> notes,
			List<Boolean> parts, @Nullable String correctAnswer) {
		this(decided, correct, score, evaluatedBy, notes, parts, correctAnswer, null);
	}

	public Evaluation {
		if (score < 0 || score > 1) {
			throw new IllegalArgumentException("score out of range: " + score);
		}
		score = Math.round(score * 1000) / 1000.0;
		notes = Set.copyOf(notes);
		parts = List.copyOf(parts);
	}

	/**
	 * What the AI judge found (EX-04), stored as {@code exercise_attempts.feedback}.
	 * @param correctedAnswer the learner's answer with its errors fixed, as close to it as
	 * possible; null when it was correct
	 * @param explanation one or two sentences for the learner, in their explanation language
	 */
	public record Feedback(List<AnswerError> errors, @Nullable String correctedAnswer, String explanation) {

		public Feedback {
			errors = List.copyOf(errors);
		}

	}

	/**
	 * One error in an answer.
	 * @param span the wrong part, as the learner wrote it; null for something missing
	 * @param grammarTopicId the learner's grammar topic it belongs to, if any (for
	 * {@code mistakes.grammar_topic_id}, PRG-04)
	 */
	public record AnswerError(ErrorType type, @Nullable String span, String explanation,
			@Nullable UUID grammarTopicId) {
	}

	/** Who decided; same values as {@code exercise_attempts_evaluated_by_ck}. */
	public enum EvaluatedBy {

		RULES, AI

	}

	/** Differences the rules accepted. */
	public enum Note {

		/** Right apart from capital letters. */
		CAPITALIZATION,
		/** Right with ae, oe, ue or ss instead of ä, ö, ü or ß. */
		UMLAUT_SPELLING,
		/** Right as one of the accepted alternatives rather than the main answer. */
		ALTERNATIVE

	}

	static Evaluation undecided(@Nullable String correctAnswer) {
		return new Evaluation(false, false, 0, EvaluatedBy.RULES, Set.of(), List.of(), correctAnswer);
	}

}
