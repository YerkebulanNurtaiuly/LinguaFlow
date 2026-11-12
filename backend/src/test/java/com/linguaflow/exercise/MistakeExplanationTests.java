package com.linguaflow.exercise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.ai.AiUseCase;
import com.linguaflow.ai.FakeLlmClient;
import com.linguaflow.ai.LlmClient;
import com.linguaflow.ai.LlmException;
import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.testsupport.TestUsers;
import com.linguaflow.testsupport.TestUsers.TestUser;
import com.linguaflow.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** Explanations of wrong answers (EX-06), against a scripted fake model. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class MistakeExplanationTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String EXPLANATION = JSON.writeValueAsString(Map.of("answerIsCorrect", false, "mistake",
			"You wrote \"den Mann\"; geben needs \"dem Mann\".", "rule",
			"The person who receives something is in the dative (Dativ): der → dem.", "topic", "Dativ", "example",
			Map.of("sentence", "Ich schenke dem Kind einen Ball.", "translation", "I give the child a ball."),
			"otherErrors", List.of()));

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ExerciseSetRepository sets;

	@Autowired
	private ExerciseRepository exercises;

	@Autowired
	private LlmClient client;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private UserRepository users;

	@Autowired
	private RegistrationService registration;

	@Autowired
	private AccessTokenService accessTokens;

	private TestUser anna;

	private TestUser ben;

	private UUID dativ;

	private FakeLlmClient fake() {
		return (FakeLlmClient) client;
	}

	@BeforeEach
	void setUp() {
		users.deleteAll();
		jdbc.update("delete from ai_cache");
		fake().reset();
		fake().respondTo(AiUseCase.MISTAKE_EXPLANATION, prompt -> EXPLANATION);
		TestUsers testUsers = new TestUsers(registration, accessTokens, jdbc);
		anna = testUsers.learner("anna@example.de");
		ben = testUsers.learner("ben@example.de");
		dativ = UUID.randomUUID();
		jdbc.update("insert into grammar_topics (id, user_id, language_code, title, normalized_title, description, "
				+ "cefr_level) values (?, ?, 'de', 'Dativ', 'dativ', 'Indirect objects', 'A2')", dativ, anna.id());
	}

	@AfterEach
	void tearDown() {
		fake().reset();
	}

	private UUID exercise(ExerciseContent content) {
		ExerciseSet set = sets.saveAndFlush(ExerciseSetRepositoryTests.newSet(anna.id(), 1));
		return exercises.saveAndFlush(new Exercise(set, 0, content, null, List.of(dativ), List.of(), null)).getId();
	}

	private String attempt(UUID exerciseId, String body) throws Exception {
		return JSON.readTree(mvc
			.perform(post("/api/exercises/{id}/attempts", exerciseId).header("Authorization", anna.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString()).get("attempt").get("id").asString();
	}

	private ResultActions explain(TestUser user, UUID exerciseId, String attemptId) throws Exception {
		return mvc.perform(post("/api/exercises/{id}/attempts/{attemptId}/explanation", exerciseId, attemptId)
			.header("Authorization", user.bearer()));
	}

	private long explanationCalls() {
		return fake().calls().stream().filter(call -> call.options().useCase() == AiUseCase.MISTAKE_EXPLANATION).count();
	}

	@Test
	void aWrongGapIsExplainedAndTheSameMistakeComesFromTheCache() throws Exception {
		UUID id = exercise(ExerciseSamples.of(ExerciseType.FILL_BLANK));
		String first = attempt(id, "{\"blanks\":[\"den\",\"das\"]}");

		explain(anna, id, first).andExpect(status().isOk())
			.andExpect(jsonPath("$.answerIsCorrect").value(false))
			.andExpect(jsonPath("$.mistake").value("You wrote \"den Mann\"; geben needs \"dem Mann\"."))
			.andExpect(jsonPath("$.rule").value("The person who receives something is in the dative (Dativ): der → dem."))
			.andExpect(jsonPath("$.topic").value("Dativ"))
			.andExpect(jsonPath("$.grammarTopicId").value(dativ.toString()))
			.andExpect(jsonPath("$.example.sentence").value("Ich schenke dem Kind einen Ball."))
			.andExpect(jsonPath("$.cached").value(false));

		String prompt = fake().calls().get(0).prompt().user();
		assertThat(prompt).contains("for a learner at level A2")
			.contains("<exercise>Ich gebe ___ Mann ___ Buch. (der Mann, das Buch)</exercise>")
			.contains("<reference_answer>Ich gebe dem Mann das Buch.</reference_answer>")
			.contains("<learner_answer>Ich gebe den Mann das Buch.</learner_answer>")
			.contains("- Dativ: Indirect objects")
			.doesNotContain("<errors>");

		// Asking again, or making the same mistake again (in other case), costs nothing
		explain(anna, id, first).andExpect(jsonPath("$.cached").value(true));
		String again = attempt(id, "{\"blanks\":[\"Den\",\"das\"]}");
		explain(anna, id, again).andExpect(jsonPath("$.cached").value(true));
		assertThat(explanationCalls()).isEqualTo(1);
		// Another wrong answer is a new explanation
		explain(anna, id, attempt(id, "{\"blanks\":[\"der\",\"das\"]}")).andExpect(jsonPath("$.cached").value(false));
		assertThat(explanationCalls()).isEqualTo(2);
		// The cache key holds the exercise and the answer, not the learner
		assertThat(jdbc.queryForObject("select count(*) from ai_cache where use_case = 'MISTAKE_EXPLANATION'",
				Long.class))
			.isEqualTo(2);
	}

	@Test
	void theJudgesErrorsGoIntoThePrompt() throws Exception {
		UUID id = exercise(translation());
		fake().respondTo(AiUseCase.ANSWER_EVALUATION, prompt -> JSON.writeValueAsString(Map.of("correct", false,
				"score", 0.7, "errors",
				List.of(Map.of("type", "WRONG_CASE", "span", "den Mann", "explanation", "Geben needs the dative.",
						"topic", "Dativ")),
				"correctedAnswer", "Ich gebe dem Mann das Buch.", "feedback", "Mind the dative.")));
		String attemptId = attempt(id, "{\"text\":\"Ich gebe den Mann das Buch.\"}");

		explain(anna, id, attemptId).andExpect(status().isOk());

		String prompt = fake().calls().get(fake().calls().size() - 1).prompt().user();
		assertThat(prompt).contains("<errors>\n- WRONG_CASE: \"den Mann\" — Geben needs the dative.\n</errors>")
			.contains("<exercise>I give the man the book.</exercise>");
	}

	@Test
	void longAnswersAndAnswersWithPersonalDataAreNotCached() throws Exception {
		UUID id = exercise(translation());
		fake().respondTo(AiUseCase.ANSWER_EVALUATION, prompt -> JSON.writeValueAsString(Map.of("correct", false,
				"score", 0.0, "errors", List.of(), "feedback", "This doesn't translate the sentence.")));
		String longAnswer = attempt(id, "{\"text\":\"" + "Ich gebe dem Mann heute ein sehr langes Buch ".repeat(3)
				+ "\"}");
		String email = attempt(id, "{\"text\":\"Schreib mir an anna@example.de\"}");

		explain(anna, id, longAnswer).andExpect(jsonPath("$.cached").value(false));
		explain(anna, id, longAnswer).andExpect(jsonPath("$.cached").value(false));
		explain(anna, id, email).andExpect(jsonPath("$.cached").value(false));
		explain(anna, id, email).andExpect(jsonPath("$.cached").value(false));

		assertThat(explanationCalls()).isEqualTo(4);
		assertThat(jdbc.queryForObject("select count(*) from ai_cache where use_case = 'MISTAKE_EXPLANATION'",
				Long.class))
			.isZero();
	}

	@Test
	void aRightAnswerHasNothingToExplain() throws Exception {
		UUID id = exercise(ExerciseSamples.of(ExerciseType.MULTIPLE_CHOICE));
		explain(anna, id, attempt(id, "{\"index\":1}")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CONFLICT"));
		assertThat(explanationCalls()).isZero();
	}

	@Test
	void onlyTheOwnersAttemptAtThatExerciseCanBeExplained() throws Exception {
		UUID id = exercise(ExerciseSamples.of(ExerciseType.MULTIPLE_CHOICE));
		UUID other = exercise(ExerciseSamples.of(ExerciseType.MULTIPLE_CHOICE));
		String attemptId = attempt(id, "{\"index\":0}");

		explain(ben, id, attemptId).andExpect(status().isNotFound());
		explain(anna, other, attemptId).andExpect(status().isNotFound());
		explain(anna, id, UUID.randomUUID().toString()).andExpect(status().isNotFound());
		explain(anna, id, attemptId).andExpect(status().isOk());
	}

	@Test
	void anAiFailureIsAServiceUnavailable() throws Exception {
		UUID id = exercise(ExerciseSamples.of(ExerciseType.WORD_ORDER));
		String attemptId = attempt(id, "{\"tokens\":[\"Ich\",\"dem\",\"gebe\",\"Hund\",\"Wasser\",\".\"]}");
		fake().failNext(new LlmException(LlmException.Kind.UNAVAILABLE, "overloaded"));

		explain(anna, id, attemptId).andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));
	}

	@Test
	void closedAnswersAreShownAsTheLearnerGaveThem() {
		assertThat(MistakeExplainer.answerText(ExerciseSamples.of(ExerciseType.FILL_BLANK),
				new ExerciseAnswer.Blanks(List.of("den"))))
			.isEqualTo("Ich gebe den Mann (nothing) Buch.");
		assertThat(MistakeExplainer.answerText(ExerciseSamples.of(ExerciseType.MULTIPLE_CHOICE),
				new ExerciseAnswer.Choice(2)))
			.isEqualTo("den");
		assertThat(MistakeExplainer.exerciseText(ExerciseSamples.of(ExerciseType.MULTIPLE_CHOICE).prompt()))
			.isEqualTo("Ich helfe ___ Frau.\nOptions: die / der / den");
		assertThat(MistakeExplainer.exerciseText(ExerciseSamples.of(ExerciseType.WORD_ORDER).prompt()))
			.isEqualTo("Put in order: dem / Ich / Hund / gebe / Wasser / .");
	}

	private static ExerciseContent translation() {
		return new ExerciseContent(ExerciseType.TRANSLATION,
				new ExercisePrompt.Translation(null, CardDirection.NATIVE_TO_TARGET, "I give the man the book."),
				new ExerciseSolution.TextAnswer("Ich gebe dem Mann das Buch.", List.of(), null));
	}

}
