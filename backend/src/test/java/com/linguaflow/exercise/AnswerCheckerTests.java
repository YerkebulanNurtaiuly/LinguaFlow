package com.linguaflow.exercise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.ai.AiUseCase;
import com.linguaflow.ai.FakeLlmClient;
import com.linguaflow.ai.InvalidLlmOutputException;
import com.linguaflow.ai.LlmClient;
import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.common.error.NotFoundException;
import com.linguaflow.exercise.Evaluation.EvaluatedBy;
import com.linguaflow.testsupport.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/** Checking open answers with the AI judge (EX-04), against a scripted fake model. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AnswerCheckerTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private AnswerChecker checker;

	@Autowired
	private ExerciseSetRepository sets;

	@Autowired
	private ExerciseRepository exercises;

	@Autowired
	private LlmClient client;

	@Autowired
	private JdbcTemplate jdbc;

	private UUID anna;

	private UUID dativ;

	private UUID perfekt;

	private FakeLlmClient fake() {
		return (FakeLlmClient) client;
	}

	@BeforeEach
	void setUp() {
		fake().reset();
		anna = TestUsers.insertLearner(jdbc);
		dativ = topic("Dativ", "Indirect objects", "A2");
		perfekt = topic("Perfekt", null, "A2");
	}

	@AfterEach
	void tearDown() {
		fake().reset();
	}

	private UUID topic(String title, String description, String level) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into grammar_topics (id, user_id, language_code, title, normalized_title, description, "
				+ "cefr_level) values (?, ?, 'de', ?, ?, ?, ?)", id, anna, title, title.toLowerCase(), description,
				level);
		return id;
	}

	/** A translation into German practising the Dativ. */
	private UUID translation(List<UUID> topicIds) {
		ExerciseSet set = sets.saveAndFlush(ExerciseSetRepositoryTests.newSet(anna, 1));
		ExerciseContent content = new ExerciseContent(ExerciseType.TRANSLATION,
				new ExercisePrompt.Translation(null, CardDirection.NATIVE_TO_TARGET, "I give the child an apple."),
				new ExerciseSolution.TextAnswer("Ich gebe dem Kind einen Apfel.",
						List.of("Dem Kind gebe ich einen Apfel."), null));
		return exercises.saveAndFlush(new Exercise(set, 0, content, null, topicIds, List.of(), null)).getId();
	}

	private static String verdict(boolean correct, double score, List<Map<String, Object>> errors,
			String correctedAnswer) {
		Map<String, Object> answer = new java.util.HashMap<>();
		answer.put("correct", correct);
		answer.put("score", score);
		answer.put("errors", errors);
		answer.put("correctedAnswer", correctedAnswer);
		answer.put("feedback", "Good word order; watch the case after geben.");
		return JSON.writeValueAsString(answer);
	}

	private static Map<String, Object> error(String type, String span, String topic) {
		Map<String, Object> error = new java.util.HashMap<>();
		error.put("type", type);
		error.put("span", span);
		error.put("explanation", "Geben takes the person in the dative.");
		error.put("topic", topic);
		return error;
	}

	@Test
	void aKnownAnswerNeedsNoAi() {
		UUID id = translation(List.of(dativ));

		Evaluation evaluation = checker.check(anna, id, new ExerciseAnswer.Text("dem Kind gebe ich einen Apfel"));

		assertThat(evaluation.correct()).isTrue();
		assertThat(evaluation.evaluatedBy()).isEqualTo(EvaluatedBy.RULES);
		assertThat(fake().calls()).isEmpty();
	}

	@Test
	void anOtherAnswerIsGradedByTheAiWithErrorsMappedToTopics() {
		UUID id = translation(List.of(dativ));
		fake().respondTo(AiUseCase.ANSWER_EVALUATION,
				prompt -> verdict(false, 0.7,
						List.of(error("WRONG_CASE", "das Kind", "dativ"), error("SPELLING", "Apfell", null)),
						"Ich gebe dem Kind einen Apfel."));

		Evaluation evaluation = checker.check(anna, id, new ExerciseAnswer.Text("Ich gebe das Kind einen Apfell."));

		assertThat(evaluation.decided()).isTrue();
		assertThat(evaluation.correct()).isFalse();
		assertThat(evaluation.score()).isEqualTo(0.7);
		assertThat(evaluation.evaluatedBy()).isEqualTo(EvaluatedBy.AI);
		assertThat(evaluation.correctAnswer()).isEqualTo("Ich gebe dem Kind einen Apfel.");
		Evaluation.Feedback feedback = evaluation.feedback();
		assertThat(feedback).isNotNull();
		assertThat(feedback.correctedAnswer()).isEqualTo("Ich gebe dem Kind einen Apfel.");
		assertThat(feedback.explanation()).contains("case");
		assertThat(feedback.errors()).extracting(Evaluation.AnswerError::type)
			.containsExactly(ErrorType.WRONG_CASE, ErrorType.SPELLING);
		assertThat(feedback.errors().get(0).grammarTopicId()).isEqualTo(dativ);
		assertThat(feedback.errors().get(0).span()).isEqualTo("das Kind");
		// Spelling belongs to no grammar topic
		assertThat(feedback.errors().get(1).grammarTopicId()).isNull();

		var call = fake().calls().get(0);
		assertThat(call.prompt().user()).contains("Grade this answer to a translation exercise for a learner at level A2.")
			.contains("<instruction>Translate the sentence into the learned language.</instruction>")
			.contains("<exercise>I give the child an apple.</exercise>")
			.contains("<reference_answer>Ich gebe dem Kind einen Apfel.</reference_answer>")
			.contains("- Dem Kind gebe ich einen Apfel.")
			.contains("- Dativ: Indirect objects")
			.contains("<learner_topics>\n- Perfekt\n</learner_topics>")
			.contains("<learner_answer>Ich gebe das Kind einen Apfell.</learner_answer>");
		assertThat(call.options().toString()).isNotBlank();
	}

	@Test
	void errorsCanBelongToTheLearnersOtherTopicsOrFallBackToTheExercisesTopic() {
		UUID id = translation(List.of(dativ));
		fake().respondTo(AiUseCase.ANSWER_EVALUATION,
				prompt -> verdict(false, 0.5,
						List.of(error("TENSE", "gab", "Perfekt: past events"), error("WORD_ORDER", "Apfel gab",
								"Satzbau"), error("MEANING", null, null)),
						"Ich habe dem Kind einen Apfel gegeben."));

		Evaluation evaluation = checker.check(anna, id, new ExerciseAnswer.Text("Ich dem Kind einen Apfel gab."));

		List<Evaluation.AnswerError> errors = evaluation.feedback().errors();
		assertThat(errors.get(0).grammarTopicId()).isEqualTo(perfekt);
		// An unknown topic on a grammar error: the exercise's only topic
		assertThat(errors.get(1).grammarTopicId()).isEqualTo(dativ);
		assertThat(errors.get(2).grammarTopicId()).isNull();
		assertThat(errors.get(2).span()).isNull();
	}

	@Test
	void withSeveralTopicsAnUnknownTopicStaysUnmapped() {
		UUID id = translation(List.of(dativ, perfekt));
		fake().respondTo(AiUseCase.ANSWER_EVALUATION, prompt -> verdict(false, 0.8,
				List.of(error("WRONG_CASE", "das", "Genitiv")), "Ich gebe dem Kind einen Apfel."));

		Evaluation evaluation = checker.check(anna, id, new ExerciseAnswer.Text("Ich gebe das Kind einen Apfel."));

		assertThat(evaluation.feedback().errors().get(0).grammarTopicId()).isNull();
		assertThat(fake().calls().get(0).prompt().user()).doesNotContain("<learner_topics>");
	}

	@Test
	void anInconsistentVerdictIsSentBackOnce() {
		UUID id = translation(List.of(dativ));
		AtomicInteger calls = new AtomicInteger();
		List<String> retryPrompts = new ArrayList<>();
		fake().respondTo(AiUseCase.ANSWER_EVALUATION, prompt -> {
			if (calls.incrementAndGet() == 1) {
				// "Correct" with an error, and a span that isn't in the answer
				return verdict(true, 0.9, List.of(error("WRONG_CASE", "den Kind", "Dativ")), null);
			}
			retryPrompts.add(prompt.user());
			return verdict(true, 1.0, List.of(), null);
		});

		Evaluation evaluation = checker.check(anna, id, new ExerciseAnswer.Text("Einen Apfel gebe ich dem Kind."));

		assertThat(evaluation.correct()).isTrue();
		assertThat(evaluation.score()).isEqualTo(1.0);
		assertThat(evaluation.feedback().errors()).isEmpty();
		assertThat(evaluation.feedback().correctedAnswer()).isNull();
		assertThat(calls.get()).isEqualTo(2);
		assertThat(retryPrompts.get(0)).contains("errors: must be empty when correct is true")
			.contains("score: must be 1 when correct is true")
			.contains("errors[0].span: must be copied exactly from the learner's answer");
	}

	@Test
	void aVerdictThatStaysInconsistentFails() {
		UUID id = translation(List.of(dativ));
		fake().respondTo(AiUseCase.ANSWER_EVALUATION, prompt -> verdict(false, 0.6, List.of(), "Ich gebe."));

		assertThatThrownBy(() -> checker.check(anna, id, new ExerciseAnswer.Text("Ich gebe Kind Apfel.")))
			.isInstanceOf(InvalidLlmOutputException.class);
	}

	@Test
	void closedFormatsNeverReachTheAi() {
		ExerciseSet set = sets.saveAndFlush(ExerciseSetRepositoryTests.newSet(anna, 1));
		UUID id = exercises.saveAndFlush(new Exercise(set, 0, ExerciseSamples.of(ExerciseType.WORD_ORDER), null,
				List.of(dativ), List.of(), null))
			.getId();

		Evaluation evaluation = checker.check(anna, id,
				new ExerciseAnswer.Tokens(List.of("Ich", "dem", "gebe", "Hund", "Wasser", ".")));

		assertThat(evaluation.evaluatedBy()).isEqualTo(EvaluatedBy.RULES);
		assertThat(evaluation.score()).isEqualTo(0.833);
		assertThat(fake().calls()).isEmpty();
	}

	@Test
	void onlyTheOwnerCanCheckAnAnswer() {
		UUID id = translation(List.of(dativ));
		UUID ben = TestUsers.insertLearner(jdbc);

		assertThatThrownBy(() -> checker.check(ben, id, new ExerciseAnswer.Text("Ich gebe dem Kind einen Apfel.")))
			.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> checker.check(anna, id, new ExerciseAnswer.Choice(0)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void theDefaultInstructionsDescribeEachOpenFormat() {
		for (ExerciseType type : List.of(ExerciseType.SENTENCE_CONSTRUCTION, ExerciseType.ERROR_CORRECTION,
				ExerciseType.TRANSFORMATION)) {
			ExercisePrompt prompt = ExerciseSamples.of(type).prompt();
			assertThat(AnswerChecker.instruction(prompt)).as(type.name()).isNotBlank();
			assertThat(AnswerChecker.exercise(prompt)).as(type.name()).isNotBlank();
		}
		assertThat(AnswerChecker.exercise(ExerciseSamples.of(ExerciseType.SENTENCE_CONSTRUCTION).prompt()))
			.isEqualTo("Say that the book belongs to your brother.\nWords to use: gehören, Bruder, Buch");
		assertThat(AnswerChecker.instruction(new ExercisePrompt.Translation("Übersetze.", CardDirection.TARGET_TO_NATIVE,
				"Hallo"))).isEqualTo("Übersetze.");
	}

}
