package com.linguaflow.exercise;

import java.util.Arrays;
import java.util.List;

import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.exercise.ExercisePrompt.ErrorCorrection;
import com.linguaflow.exercise.ExercisePrompt.FillBlank;
import com.linguaflow.exercise.ExercisePrompt.MultipleChoice;
import com.linguaflow.exercise.ExercisePrompt.SentenceConstruction;
import com.linguaflow.exercise.ExercisePrompt.Transformation;
import com.linguaflow.exercise.ExercisePrompt.Translation;
import com.linguaflow.exercise.ExercisePrompt.WordOrder;
import com.linguaflow.exercise.ExerciseSolution.Blank;
import com.linguaflow.exercise.ExerciseSolution.Blanks;
import com.linguaflow.exercise.ExerciseSolution.Choice;
import com.linguaflow.exercise.ExerciseSolution.TextAnswer;
import com.linguaflow.exercise.ExerciseSolution.TokenOrder;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class ExerciseRulesTests {

	private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

	@ParameterizedTest
	@EnumSource(ExerciseType.class)
	void theSamplesAreValid(ExerciseType type) {
		ExerciseContent content = ExerciseSamples.of(type);
		assertThat(ExerciseRules.problems(content)).isEmpty();
		assertThat(VALIDATOR.validate(content.prompt())).isEmpty();
		assertThat(VALIDATOR.validate(content.solution())).isEmpty();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "Ich gebe ___ Mann ___ Buch.|2", "Ein ______ Wort.|1", "Keine Lücke.|0",
			"Zu kurz: __|0", "___|1" })
	void gapsAreThreeOrMoreUnderscores(String text, int gaps) {
		assertThat(ExerciseRules.gapCount(text)).isEqualTo(gaps);
	}

	@Test
	void fillBlankNeedsOneAnswerPerGap() {
		Blanks one = new Blanks(List.of(new Blank("dem", List.of())), null);
		assertThat(problems(ExerciseType.FILL_BLANK, new FillBlank(null, "Ich gebe ___ Mann ___ Buch.", null), one))
			.containsExactly("solution.blanks: must have one entry per ___ gap: the text has 2 but there are 1");
		assertThat(problems(ExerciseType.FILL_BLANK, new FillBlank(null, "Ich gebe dem Mann das Buch.", null), one))
			.containsExactly("prompt.text: must contain at least one gap written as ___");
	}

	@Test
	void multipleChoiceOptionsAreDistinctAndTheIndexPointsAtOne() {
		MultipleChoice prompt = new MultipleChoice(null, "Ich helfe ___ Frau.", List.of("der", " Der ", "die"));
		assertThat(problems(ExerciseType.MULTIPLE_CHOICE, prompt, new Choice(3, null))).containsExactly(
				"prompt.options[1]: repeats an earlier entry; each must be different",
				"solution.correctIndex: must be between 0 and 2, the index of the correct option");
		assertThat(problems(ExerciseType.MULTIPLE_CHOICE, prompt, new Choice(-1, null)))
			.contains("solution.correctIndex: must be between 0 and 2, the index of the correct option");
	}

	@Test
	void wordOrderSolutionsUseExactlyThePromptsTokens() {
		WordOrder prompt = new WordOrder(null, List.of("dem", "Ich", "Hund", "gebe", "Wasser"));
		assertThat(problems(ExerciseType.WORD_ORDER, prompt,
				new TokenOrder(List.of("Ich", "gebe", "den", "Hund", "Wasser"),
						List.of(List.of("Wasser", "gebe", "ich", "den", "Hund")), null)))
			.containsExactly("solution.tokens: must use exactly the prompt's tokens, each as often as in the prompt",
					"solution.alternatives[0]: must use exactly the prompt's tokens");
		// A token used twice must appear twice
		assertThat(problems(ExerciseType.WORD_ORDER, new WordOrder(null, List.of("die", "Katze", "die")),
				new TokenOrder(List.of("die", "Katze"), List.of(), null)))
			.hasSize(1);
	}

	@Test
	void wordOrderPromptsMustBeShuffled() {
		List<String> sentence = List.of("Ich", "gebe", "dem", "Hund", "Wasser");
		assertThat(problems(ExerciseType.WORD_ORDER, new WordOrder(null, sentence),
				new TokenOrder(sentence, List.of(), null)))
			.containsExactly("prompt.tokens: must be shuffled, not already in the correct order");
		// Identical tokens can't be shuffled
		assertThat(problems(ExerciseType.WORD_ORDER, new WordOrder(null, List.of("ja", "ja")),
				new TokenOrder(List.of("ja", "ja"), List.of(), null)))
			.isEmpty();
	}

	@Test
	void correctionsAndTransformationsMustChangeTheSentence() {
		TextAnswer same = new TextAnswer("ich danke  dich für die Hilfe.", List.of(), null);
		assertThat(problems(ExerciseType.ERROR_CORRECTION, new ErrorCorrection(null, "Ich danke dich für die Hilfe."),
				same))
			.containsExactly("solution.answer: must correct the mistake in the sentence, not repeat it");
		assertThat(problems(ExerciseType.TRANSFORMATION,
				new Transformation(null, "Ich danke dich für die Hilfe.", "Put it into the Perfekt."), same))
			.containsExactly("solution.answer: must be the transformed sentence, not the original");
	}

	@Test
	void sentenceConstructionWordsAreDistinct() {
		assertThat(problems(ExerciseType.SENTENCE_CONSTRUCTION,
				new SentenceConstruction(null, "Say you help your mother.", List.of("helfen", "Mutter", "helfen")),
				new TextAnswer("Ich helfe meiner Mutter.", List.of(), null)))
			.containsExactly("prompt.words[2]: repeats an earlier entry; each must be different");
	}

	@Test
	void anyTranslationIsConsistent() {
		assertThat(problems(ExerciseType.TRANSLATION, new Translation(null, CardDirection.TARGET_TO_NATIVE, "Hallo"),
				new TextAnswer("Hello", List.of(), null)))
			.isEmpty();
	}

	@Test
	void beanValidationCatchesMissingAndOversizedFields() {
		assertThat(VALIDATOR.validate(new MultipleChoice(null, " ", List.of("nur eine")))).hasSize(2);
		assertThat(VALIDATOR.validate(new Blanks(List.of(), null))).hasSize(1);
		// Null elements are kept, so they're reported rather than failing the copy
		assertThat(VALIDATOR.validate(new TextAnswer("Ja.", Arrays.asList("Jawohl.", null), null))).hasSize(1);
		assertThat(VALIDATOR.validate(new Blanks(List.of(new Blank("", List.of())), null))).hasSize(1);
	}

	private static List<String> problems(ExerciseType type, ExercisePrompt prompt, ExerciseSolution solution) {
		return ExerciseRules.problems(new ExerciseContent(type, prompt, solution));
	}

}
