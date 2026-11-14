package com.linguaflow.exercise;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.linguaflow.ai.AiCaller;
import com.linguaflow.ai.OutputCheck;
import com.linguaflow.ai.prompt.PromptTemplate;
import com.linguaflow.ai.prompt.PromptVar;
import com.linguaflow.ai.prompt.PromptVariables;
import com.linguaflow.ai.prompt.RenderedPrompt;
import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.testsupport.EvalReport;
import com.linguaflow.testsupport.LiveAi;
import com.linguaflow.testsupport.SampleAnswers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Live eval of the exercise AI calls (QA-03): answer grading against learner answers
 * whose verdict is known, and one generation call whose exercises must pass the
 * production rules. Off by default because it costs money; the nightly {@code ai-eval}
 * workflow runs it with {@code -Dai.live=true} and {@code AI_API_KEY}.
 */
@EnabledIfSystemProperty(named = "ai.live", matches = "true")
class ExerciseAiEvalTests {

	private static final String DATIV = "Dativ: The dative case for indirect objects and after some prepositions";

	private static final String PERFEKT = "Perfekt: The spoken past tense with haben or sein";

	/** A learner's answer to a translation and the verdict a teacher would give. */
	private record GradingCase(String english, String reference, List<String> alternatives, String topic,
			String answer, boolean correct, Set<ErrorType> acceptableErrors) {
	}

	private static final List<GradingCase> GRADING = List.of(
			new GradingCase("I give the dog water.", "Ich gebe dem Hund Wasser.", List.of(), DATIV,
					"Ich gebe den Hund Wasser.", false, Set.of(ErrorType.WRONG_CASE, ErrorType.WRONG_ARTICLE)),
			new GradingCase("I give the dog water.", "Ich gebe dem Hund Wasser.", List.of(), DATIV,
					"Dem Hund gebe ich Wasser.", true, Set.of()),
			new GradingCase("I give the dog water.", "Ich gebe dem Hund Wasser.", List.of(), DATIV,
					"Ich gebe der Katze Wasser.", false, Set.of(ErrorType.MEANING, ErrorType.WRONG_WORD)),
			new GradingCase("Yesterday I went home.", "Gestern bin ich nach Hause gegangen.", List.of(), PERFEKT,
					"Ich bin gestern nach Hause gegangen.", true, Set.of()),
			new GradingCase("Yesterday I went home.", "Gestern bin ich nach Hause gegangen.", List.of(), PERFEKT,
					"Gestern habe ich nach Hause gegangen.", false, Set.of(ErrorType.VERB_FORM, ErrorType.TENSE)),
			new GradingCase("I help my brother.", "Ich helfe meinem Bruder.", List.of(), DATIV,
					"Ich helfe meinen Bruder.", false,
					Set.of(ErrorType.WRONG_CASE, ErrorType.WRONG_ENDING, ErrorType.WRONG_ARTICLE)));

	@Test
	void gradesAnswersLikeATeacher() {
		EvalReport report = new EvalReport("answer-evaluation", 0.8);
		try (LiveAi ai = LiveAi.start()) {
			for (GradingCase gradingCase : GRADING) {
				String name = "\"" + gradingCase.answer() + "\"";
				try {
					JudgeAnswer verdict = grade(ai, gradingCase);
					boolean errorsFit = gradingCase.correct() || verdict.errors()
						.stream()
						.anyMatch(error -> gradingCase.acceptableErrors().contains(error.type()));
					report.check(name, (verdict.correct() ? "correct" : "wrong") + " " + verdict.score() + " "
							+ verdict.errors().stream().map(error -> error.type().name()).toList(),
							verdict.correct() == gradingCase.correct() && errorsFit);
				}
				catch (RuntimeException ex) {
					report.fail(name, ex.getClass().getSimpleName() + ": " + ex.getMessage());
				}
			}
		}
		report.finish();
	}

	private static JudgeAnswer grade(LiveAi ai, GradingCase gradingCase) {
		ExercisePrompt prompt = new ExercisePrompt.Translation(null, CardDirection.NATIVE_TO_TARGET,
				gradingCase.english());
		RenderedPrompt rendered = ai.prompts()
			.render(PromptTemplate.ANSWER_EVALUATION, PromptVariables.forLanguages("de", "en")
				.set(PromptVar.EXERCISE_TYPE, AnswerChecker.typeName(ExerciseType.TRANSLATION))
				.set(PromptVar.CEFR_LEVEL, "A2")
				.set(PromptVar.INSTRUCTION, AnswerChecker.instruction(prompt))
				.set(PromptVar.EXERCISE, AnswerChecker.exercise(prompt))
				.set(PromptVar.REFERENCE_ANSWER, gradingCase.reference())
				.set(PromptVar.ACCEPTED_ANSWERS, gradingCase.alternatives())
				.set(PromptVar.FOCUS_TOPICS, List.of(gradingCase.topic()))
				.set(PromptVar.LEARNER_ANSWER, gradingCase.answer()));
		return ai.llm()
			.complete(AiCaller.system(), rendered, JudgeAnswer.class, AnswerChecker.checkFor(gradingCase.answer()))
			.value();
	}

	@Test
	void writesValidFillInTheBlankExercises() {
		EvalReport report = new EvalReport("exercise-generation", 0.75);
		ExerciseType type = ExerciseType.FILL_BLANK;
		try (LiveAi ai = LiveAi.start()) {
			RenderedPrompt rendered = ai.prompts()
				.render(PromptTemplate.exercise(type), PromptVariables.forLanguages("de", "en")
					.set(PromptVar.COUNT, "4")
					.set(PromptVar.CEFR_LEVEL, "A2")
					.set(PromptVar.FOCUS_TOPICS, List.of(DATIV))
					.set(PromptVar.PRACTICE_VOCABULARY,
							List.of("der Hund (dog)", "geben (to give)", "helfen (to help)", "die Frau (woman)")));
			GeneratedExercises.Batch batch = ai.llm()
				.complete(AiCaller.system(), rendered, GeneratedExercises.batchType(type), OutputCheck.none())
				.value();
			report.check("asked for 4", String.valueOf(batch.exercises().size()), batch.exercises().size() == 4);
			for (GeneratedExercises.Item item : batch.exercises()) {
				Optional<ExerciseContent> content = ExerciseGenerator.check(SampleAnswers.VALIDATOR, type, item);
				String text = item.prompt() instanceof ExercisePrompt.FillBlank fill ? fill.text() : "?";
				report.check("valid: " + text, content.map(ExerciseContent::summary).orElse("rejected by the rules"),
						content.isPresent() && item.topics().stream().anyMatch(topic -> topic.startsWith("Dativ")));
			}
		}
		catch (RuntimeException ex) {
			report.fail("generation call", ex.getClass().getSimpleName() + ": " + ex.getMessage());
		}
		report.finish();
	}

}
