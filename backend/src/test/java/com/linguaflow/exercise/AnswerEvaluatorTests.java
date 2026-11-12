package com.linguaflow.exercise;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.exercise.AnswerEvaluator.Options;
import com.linguaflow.exercise.Evaluation.EvaluatedBy;
import com.linguaflow.exercise.Evaluation.Note;
import com.linguaflow.exercise.ExerciseSolution.Blank;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerEvaluatorTests {

	private static final Options DEFAULT = Options.DEFAULT;

	private static final Options STRICT = new Options(false, true);

	private static Set<Note> notes(String names) {
		return names.isBlank() ? Set.of()
				: Set.copyOf(Arrays.stream(names.split(" ")).map(Note::valueOf).toList());
	}

	@Nested
	class Normalizing {

		@ParameterizedTest
		@CsvSource(delimiter = '|', value = { "  Ich  gehe nach Hause. |Ich gehe nach Hause",
				"„Hallo“, sagte er!|Hallo sagte er", "Ja - gern|Ja gern", "Die E-Mail|Die E-Mail",
				"Wie geht’s?|Wie geht's", "Straße|Straße" })
		void punctuationAndSpacingNeverMatter(String text, String expected) {
			assertThat(AnswerNormalizer.exact(text)).isEqualTo(expected);
		}

		@Test
		void umlautsFoldKeepingCase() {
			assertThat(AnswerNormalizer.foldUmlauts("Über die Straße, Äpfel, schön")).isEqualTo(
					"Ueber die Strasse, Aepfel, schoen");
			assertThat(AnswerNormalizer.exact(null)).isEmpty();
		}

	}

	@Nested
	class Matching {

		@ParameterizedTest(name = "\"{0}\" for \"{1}\" → {2} [{3}]")
		@CsvSource(delimiter = '|', value = { //
				"dem|dem|true|", //
				"Dem|dem|true|CAPITALIZATION", //
				" dem. |dem|true|", //
				"den|dem|false|", //
				"Muell|Müll|true|UMLAUT_SPELLING", //
				"muell|Müll|true|UMLAUT_SPELLING CAPITALIZATION", //
				"Strasse|Straße|true|UMLAUT_SPELLING", //
				"zum|zu dem|true|ALTERNATIVE", //
				"Zum|zu dem|true|ALTERNATIVE CAPITALIZATION", //
				"' '|dem|false|" })
		void answersAndAlternatives(String given, String answer, boolean matches, String expectedNotes) {
			List<String> alternatives = answer.equals("zu dem") ? List.of("zum") : List.of();
			var result = AnswerEvaluator.match(given, answer, alternatives, DEFAULT);
			assertThat(result.isPresent()).isEqualTo(matches);
			result.ifPresent(found -> assertThat(found).isEqualTo(notes(expectedNotes == null ? "" : expectedNotes)));
		}

		@Test
		void strictCaseAndNoUmlautAlternativesCanBeConfigured() {
			assertThat(AnswerEvaluator.match("Dem", "dem", List.of(), STRICT)).isEmpty();
			assertThat(AnswerEvaluator.match("Muell", "Müll", List.of(), STRICT)).isEmpty();
			assertThat(AnswerEvaluator.match("Muell", "Müll", List.of(), new Options(true, true)))
				.contains(Set.of(Note.UMLAUT_SPELLING));
			assertThat(AnswerEvaluator.match("muell", "Müll", List.of(), new Options(true, true))).isEmpty();
		}

	}

	@Nested
	class FillBlank {

		private final ExerciseContent twoGaps = new ExerciseContent(ExerciseType.FILL_BLANK,
				new ExercisePrompt.FillBlank(null, "Ich gebe ___ Mann ___ Buch.", null),
				new ExerciseSolution.Blanks(
						List.of(new Blank("dem", List.of()), new Blank("das", List.of("ein"))), null));

		@Test
		void everyGapRightIsCorrect() {
			Evaluation evaluation = evaluate(twoGaps, new ExerciseAnswer.Blanks(List.of("Dem", "ein")));
			assertThat(evaluation.decided()).isTrue();
			assertThat(evaluation.correct()).isTrue();
			assertThat(evaluation.score()).isEqualTo(1.0);
			assertThat(evaluation.evaluatedBy()).isEqualTo(EvaluatedBy.RULES);
			assertThat(evaluation.notes()).containsExactlyInAnyOrder(Note.CAPITALIZATION, Note.ALTERNATIVE);
			assertThat(evaluation.parts()).containsExactly(true, true);
			assertThat(evaluation.correctAnswer()).isEqualTo("Ich gebe dem Mann das Buch.");
		}

		@Test
		void eachGapEarnsPartialCredit() {
			Evaluation evaluation = evaluate(twoGaps, new ExerciseAnswer.Blanks(List.of("den", "das")));
			assertThat(evaluation.correct()).isFalse();
			assertThat(evaluation.score()).isEqualTo(0.5);
			assertThat(evaluation.parts()).containsExactly(false, true);
		}

		@Test
		void missingGapsAreWrong() {
			assertThat(evaluate(twoGaps, new ExerciseAnswer.Blanks(List.of("dem"))).parts()).containsExactly(true,
					false);
			assertThat(evaluate(twoGaps, new ExerciseAnswer.Blanks(Arrays.asList(null, "das"))).score())
				.isEqualTo(0.5);
			assertThat(evaluate(twoGaps, new ExerciseAnswer.Blanks(null)).score()).isZero();
		}

	}

	@Nested
	class MultipleChoice {

		private final ExerciseContent content = ExerciseSamples.of(ExerciseType.MULTIPLE_CHOICE);

		@Test
		void theChosenIndexDecides() {
			Evaluation right = evaluate(content, new ExerciseAnswer.Choice(1));
			assertThat(right.correct()).isTrue();
			assertThat(right.score()).isEqualTo(1.0);
			assertThat(right.correctAnswer()).isEqualTo("der");
			assertThat(evaluate(content, new ExerciseAnswer.Choice(0)).correct()).isFalse();
			assertThat(evaluate(content, new ExerciseAnswer.Choice(null)).score()).isZero();
			assertThat(evaluate(content, new ExerciseAnswer.Choice(9)).decided()).isTrue();
		}

	}

	@Nested
	class WordOrder {

		// Solution: Ich gebe dem Hund Wasser . — alternative: Wasser gebe ich dem Hund .
		private final ExerciseContent content = ExerciseSamples.of(ExerciseType.WORD_ORDER);

		@Test
		void theSolutionOrAnAlternativeIsCorrect() {
			Evaluation solution = evaluate(content, tokens("Ich gebe dem Hund Wasser ."));
			assertThat(solution.correct()).isTrue();
			assertThat(solution.score()).isEqualTo(1.0);
			assertThat(solution.notes()).isEmpty();
			assertThat(solution.correctAnswer()).isEqualTo("Ich gebe dem Hund Wasser .");

			// The learner keeps the prompt's capital "Ich" in the middle: case doesn't matter
			Evaluation alternative = evaluate(content, tokens("Wasser gebe Ich dem Hund ."));
			assertThat(alternative.correct()).isTrue();
			assertThat(alternative.notes()).containsExactly(Note.ALTERNATIVE);
		}

		@ParameterizedTest(name = "{0} → {1}")
		@CsvSource(delimiter = '|', value = {
				// One word out of place: 5 of 6 tokens keep their order
				"Ich dem gebe Hund Wasser .|0.833|true false true true true true",
				// Verb at the end
				"Ich dem Hund Wasser gebe .|0.833|true true true true false true",
				// Reversed: closest to the alternative (Wasser … gebe … ich)
				". Wasser Hund dem gebe Ich|0.5|false true false false true true" })
		void wrongOrdersEarnPartialCredit(String answer, double score, String parts) {
			Evaluation evaluation = evaluate(content, tokens(answer));
			assertThat(evaluation.correct()).isFalse();
			assertThat(evaluation.score()).isEqualTo(score);
			assertThat(evaluation.parts())
				.containsExactly(Arrays.stream(parts.split(" ")).map(Boolean::valueOf).toArray(Boolean[]::new));
		}

		@Test
		void missingOrExtraTokensLowerTheScore() {
			assertThat(evaluate(content, tokens("Ich gebe dem Hund")).score()).isEqualTo(0.667);
			assertThat(evaluate(content, tokens("Ich gebe dem Hund Wasser . heute")).score()).isEqualTo(0.857);
			assertThat(evaluate(content, new ExerciseAnswer.Tokens(List.of())).score()).isZero();
		}

		@Test
		void aWrongOrderNeverGetsFullMarks() {
			List<String> words = new java.util.ArrayList<>();
			for (int i = 0; i < 2000; i++) {
				words.add("w" + i);
			}
			List<String> swapped = new java.util.ArrayList<>(words);
			java.util.Collections.swap(swapped, 0, 1);
			ExerciseContent longOne = new ExerciseContent(ExerciseType.WORD_ORDER,
					new ExercisePrompt.WordOrder(null, swapped), new ExerciseSolution.TokenOrder(words, List.of(), null));
			Evaluation evaluation = evaluate(longOne, new ExerciseAnswer.Tokens(swapped));
			assertThat(evaluation.correct()).isFalse();
			assertThat(evaluation.score()).isLessThan(1.0);
		}

		private static ExerciseAnswer.Tokens tokens(String sentence) {
			return new ExerciseAnswer.Tokens(List.of(sentence.split(" ")));
		}

	}

	@Nested
	class OpenAnswers {

		private final ExerciseContent translation = new ExerciseContent(ExerciseType.TRANSLATION,
				new ExercisePrompt.Translation(null, CardDirection.NATIVE_TO_TARGET, "I give the child an apple."),
				new ExerciseSolution.TextAnswer("Ich gebe dem Kind einen Apfel.",
						List.of("Dem Kind gebe ich einen Apfel."), null));

		@ParameterizedTest
		@CsvSource(delimiter = '|',
				value = { "Ich gebe dem Kind einen Apfel|", "ich gebe dem kind einen apfel!|CAPITALIZATION",
						"Dem Kind gebe ich einen Apfel.|ALTERNATIVE" })
		void aKnownAnswerIsDecidedByTheRules(String answer, String expectedNotes) {
			Evaluation evaluation = evaluate(translation, new ExerciseAnswer.Text(answer));
			assertThat(evaluation.decided()).isTrue();
			assertThat(evaluation.correct()).isTrue();
			assertThat(evaluation.notes()).isEqualTo(notes(expectedNotes == null ? "" : expectedNotes));
		}

		@Test
		void anythingElseGoesToTheAiJudge() {
			Evaluation evaluation = evaluate(translation, new ExerciseAnswer.Text("Ich schenke dem Kind einen Apfel."));
			assertThat(evaluation.decided()).isFalse();
			assertThat(evaluation.correctAnswer()).isEqualTo("Ich gebe dem Kind einen Apfel.");
		}

		@Test
		void anEmptyAnswerIsSimplyWrong() {
			Evaluation evaluation = evaluate(translation, new ExerciseAnswer.Text("  . "));
			assertThat(evaluation.decided()).isTrue();
			assertThat(evaluation.correct()).isFalse();
		}

		@Test
		void everyOpenTypeTakesText() {
			for (ExerciseType type : List.of(ExerciseType.SENTENCE_CONSTRUCTION, ExerciseType.ERROR_CORRECTION,
					ExerciseType.TRANSFORMATION)) {
				ExerciseContent content = ExerciseSamples.of(type);
				String answer = ((ExerciseSolution.TextAnswer) content.solution()).answer();
				assertThat(evaluate(content, new ExerciseAnswer.Text(answer)).correct()).as(type.name()).isTrue();
			}
		}

	}

	@Test
	void anAnswerOfTheWrongShapeIsRefused() {
		assertThatThrownBy(
				() -> evaluate(ExerciseSamples.of(ExerciseType.FILL_BLANK), new ExerciseAnswer.Choice(0)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("A FILL_BLANK exercise takes a Blanks answer");
	}

	private static Evaluation evaluate(ExerciseContent content, ExerciseAnswer answer) {
		return AnswerEvaluator.evaluate(content, answer, DEFAULT);
	}

}
