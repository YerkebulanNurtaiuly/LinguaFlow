package com.linguaflow.exercise;

import java.util.List;
import java.util.Optional;

import com.linguaflow.ai.AiCaller;
import com.linguaflow.ai.AiUseCase;
import com.linguaflow.ai.InvalidLlmOutputException;
import com.linguaflow.ai.LlmPrompt;
import com.linguaflow.ai.OutputCheck;
import com.linguaflow.ai.StructuredResponse;
import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.testsupport.SampleAnswers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Contract of the exercise AI calls (QA-03): sample model answers for generation,
 * answer grading and mistake explanations go through the production records and checks,
 * as {@link ExerciseGenerator}, {@link AnswerChecker} and {@link MistakeExplainer} use them.
 */
class ExerciseAiContractTests {

	private static final LlmPrompt PROMPT = new LlmPrompt("system", "user");

	private static final String LEARNER_ANSWER = "Ich gebe den Hund Wasser.";

	private static GeneratedExercises.Batch generated(ExerciseType type, String sample) {
		StructuredResponse<? extends GeneratedExercises.Batch> response = SampleAnswers
			.llmAnswering(SampleAnswers.read("exercise-generation/" + sample))
			.complete(AiCaller.system(), AiUseCase.EXERCISE_GENERATION, PROMPT, GeneratedExercises.batchType(type),
					OutputCheck.none());
		assertThat(response.attempts()).isEqualTo(1);
		return response.value();
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource({ "FILL_BLANK, fill-blank.json, 2", "MULTIPLE_CHOICE, multiple-choice.json, 1",
			"TRANSLATION, translation.json, 1", "WORD_ORDER, word-order.json, 1",
			"SENTENCE_CONSTRUCTION, sentence-construction.json, 1", "ERROR_CORRECTION, error-correction.json, 1",
			"TRANSFORMATION, transformation.json, 1" })
	void everyGeneratedExerciseIsAccepted(ExerciseType type, String sample, int count) {
		GeneratedExercises.Batch batch = generated(type, sample);

		assertThat(batch.exercises()).hasSize(count);
		for (GeneratedExercises.Item item : batch.exercises()) {
			Optional<ExerciseContent> content = ExerciseGenerator.check(SampleAnswers.VALIDATOR, type, item);
			assertThat(content).as("%s is a valid exercise", item).isPresent();
			assertThat(item.topics()).isNotEmpty();
		}
	}

	@Test
	void anItemBreakingTheRulesIsDroppedAndTheRestKept() {
		GeneratedExercises.Batch batch = generated(ExerciseType.FILL_BLANK, "invalid-fill-blank-gap-count.json");

		List<Optional<ExerciseContent>> checked = batch.exercises()
			.stream()
			.map(item -> ExerciseGenerator.check(SampleAnswers.VALIDATOR, ExerciseType.FILL_BLANK, item))
			.toList();
		// Two gaps but one blank: dropped; the second item stays
		assertThat(checked.get(0)).isEmpty();
		assertThat(checked.get(1)).isPresent();
	}

	private static JudgeAnswer judged(String sample) {
		return SampleAnswers.llmAnswering(SampleAnswers.read("answer-evaluation/" + sample))
			.complete(AiCaller.system(), AiUseCase.ANSWER_EVALUATION, PROMPT, JudgeAnswer.class,
					AnswerChecker.checkFor(LEARNER_ANSWER))
			.value();
	}

	@Test
	void aWrongAnswerIsGradedWithItsError() {
		JudgeAnswer verdict = judged("wrong-case.json");

		assertThat(verdict.correct()).isFalse();
		assertThat(verdict.score()).isEqualTo(0.7);
		assertThat(verdict.errors()).singleElement().satisfies(error -> {
			assertThat(error.type()).isEqualTo(ErrorType.WRONG_CASE);
			assertThat(error.span()).isEqualTo("den Hund");
			assertThat(error.topic()).isEqualTo("Dativ");
		});
	}

	@Test
	void aRightAnswerIsGradedWithoutErrors() {
		JudgeAnswer verdict = judged("correct.json");

		assertThat(verdict.correct()).isTrue();
		assertThat(verdict.score()).isEqualTo(1.0);
		assertThat(verdict.errors()).isEmpty();
	}

	@Test
	void anErrorPointingOutsideTheAnswerIsSentBack() {
		InvalidLlmOutputException failure = catchThrowableOfType(InvalidLlmOutputException.class,
				() -> judged("invalid-span-not-in-answer.json"));

		assertThat(failure.problems()).singleElement().asString().startsWith("errors[0].span");
	}

	@Test
	void aMistakeExplanationIsAccepted() {
		MistakeExplanation explanation = SampleAnswers
			.llmAnswering(SampleAnswers.read("mistake-explanation/wrong-case.json"))
			.complete(AiCaller.system(), AiUseCase.MISTAKE_EXPLANATION, PROMPT, MistakeExplanation.class,
					OutputCheck.none())
			.value();

		assertThat(explanation.answerIsCorrect()).isFalse();
		assertThat(explanation.topic()).isEqualTo("Dativ");
		assertThat(explanation.example().sentence()).isEqualTo("Sie gibt dem Kind einen Apfel.");
		assertThat(explanation.otherErrors()).isEmpty();
	}

}
