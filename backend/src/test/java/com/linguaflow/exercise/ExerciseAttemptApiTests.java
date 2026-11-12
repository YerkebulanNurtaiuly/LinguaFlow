package com.linguaflow.exercise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.linguaflow.testsupport.OwnershipAssertions;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** Answering exercises end to end (EX-05). */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ExerciseAttemptApiTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ApplicationEvents events;

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

	private UUID word;

	private FakeLlmClient fake() {
		return (FakeLlmClient) client;
	}

	@BeforeEach
	void setUp() {
		users.deleteAll();
		fake().reset();
		TestUsers testUsers = new TestUsers(registration, accessTokens, jdbc);
		anna = testUsers.learner("anna@example.de");
		ben = testUsers.learner("ben@example.de");
		dativ = UUID.randomUUID();
		jdbc.update("insert into grammar_topics (id, user_id, language_code, title, normalized_title, cefr_level) "
				+ "values (?, ?, 'de', 'Dativ', 'dativ', 'A2')", dativ, anna.id());
		word = UUID.randomUUID();
		jdbc.update("insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma) "
				+ "values (?, ?, 'de', 'der Mann', 'mann')", word, anna.id());
	}

	@AfterEach
	void tearDown() {
		fake().reset();
	}

	private UUID exercise(ExerciseContent content) {
		ExerciseSet set = sets.saveAndFlush(ExerciseSetRepositoryTests.newSet(anna.id(), 1));
		return exercises.saveAndFlush(new Exercise(set, 0, content, ExerciseDifficulty.EASY, List.of(dativ),
				List.of(word), null))
			.getId();
	}

	private ResultActions answer(TestUser user, UUID exerciseId, String body) throws Exception {
		return mvc.perform(post("/api/exercises/{id}/attempts", exerciseId).header("Authorization", user.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private long count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Long.class);
	}

	@Test
	void aPartlyWrongGapAnswerIsStoredWithItsMistakes() throws Exception {
		UUID id = exercise(ExerciseSamples.of(ExerciseType.FILL_BLANK));

		answer(anna, id, "{\"blanks\":[\"dem\",\"die\"],\"durationMs\":5400}").andExpect(status().isCreated())
			.andExpect(jsonPath("$.correct").value(false))
			.andExpect(jsonPath("$.score").value(0.5))
			.andExpect(jsonPath("$.evaluatedBy").value("RULES"))
			.andExpect(jsonPath("$.parts[0]").value(true))
			.andExpect(jsonPath("$.parts[1]").value(false))
			.andExpect(jsonPath("$.correctAnswer").value("Ich gebe dem Mann das Buch."))
			.andExpect(jsonPath("$.explanation").value("Geben takes the person in the Dativ."))
			.andExpect(jsonPath("$.attempt.answer.blanks[1]").value("die"))
			.andExpect(jsonPath("$.attempt.score").value(0.5))
			.andExpect(jsonPath("$.attempt.durationMs").value(5400))
			.andExpect(jsonPath("$.mistakes.length()").value(1))
			.andExpect(jsonPath("$.mistakes[0].errorType").value("WRONG_GAP"))
			.andExpect(jsonPath("$.mistakes[0].excerpt").value("die"))
			.andExpect(jsonPath("$.mistakes[0].grammarTopicId").value(dativ.toString()));

		assertThat(jdbc.queryForObject("select score from exercise_attempts", java.math.BigDecimal.class))
			.isEqualByComparingTo("0.500");
		assertThat(jdbc.queryForObject("select answer ->> 'blanks' from exercise_attempts", String.class))
			.isEqualTo("[\"dem\", \"die\"]");
		assertThat(jdbc.queryForObject("select evaluated_by from exercise_attempts", String.class)).isEqualTo("RULES");
		assertThat(jdbc.queryForObject("select feedback is null from exercise_attempts", Boolean.class)).isTrue();

		List<ExerciseAttemptedEvent> published = events.stream(ExerciseAttemptedEvent.class).toList();
		assertThat(published).hasSize(1);
		ExerciseAttemptedEvent event = published.get(0);
		assertThat(event.userId()).isEqualTo(anna.id());
		assertThat(event.languageCode()).isEqualTo("de");
		assertThat(event.exerciseId()).isEqualTo(id);
		assertThat(event.type()).isEqualTo(ExerciseType.FILL_BLANK);
		assertThat(event.correct()).isFalse();
		assertThat(event.score()).isEqualTo(0.5);
		assertThat(event.grammarTopicIds()).containsExactly(dativ);
		assertThat(event.vocabularyItemIds()).containsExactly(word);
	}

	@Test
	void aRightChoiceHasNoMistakes() throws Exception {
		UUID id = exercise(ExerciseSamples.of(ExerciseType.MULTIPLE_CHOICE));

		answer(anna, id, "{\"index\":1}").andExpect(status().isCreated())
			.andExpect(jsonPath("$.correct").value(true))
			.andExpect(jsonPath("$.correctAnswer").value("der"))
			.andExpect(jsonPath("$.mistakes.length()").value(0))
			.andExpect(jsonPath("$.feedback").doesNotExist());
		answer(anna, id, "{\"index\":2}").andExpect(jsonPath("$.mistakes[0].errorType").value("WRONG_CHOICE"))
			.andExpect(jsonPath("$.mistakes[0].excerpt").value("den"));
		assertThat(count("exercise_attempts")).isEqualTo(2);
		assertThat(count("mistakes")).isEqualTo(1);
	}

	@Test
	void aWrongWordOrderEarnsPartialCredit() throws Exception {
		UUID id = exercise(ExerciseSamples.of(ExerciseType.WORD_ORDER));

		answer(anna, id, "{\"tokens\":[\"Ich\",\"dem\",\"gebe\",\"Hund\",\"Wasser\",\".\"]}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.score").value(0.833))
			.andExpect(jsonPath("$.alternatives[0]").value("Wasser gebe ich dem Hund ."))
			.andExpect(jsonPath("$.mistakes[0].errorType").value("WORD_ORDER"))
			.andExpect(jsonPath("$.mistakes[0].excerpt").value("Ich dem gebe Hund Wasser ."));
	}

	@Test
	void anOpenAnswerIsGradedByTheAi() throws Exception {
		UUID id = exercise(new ExerciseContent(ExerciseType.TRANSLATION,
				new ExercisePrompt.Translation(null, CardDirection.NATIVE_TO_TARGET, "I give the man the book."),
				new ExerciseSolution.TextAnswer("Ich gebe dem Mann das Buch.", List.of(), null)));
		fake().respondTo(AiUseCase.ANSWER_EVALUATION, prompt -> JSON.writeValueAsString(Map.of("correct", false,
				"score", 0.7, "errors",
				List.of(Map.of("type", "WRONG_CASE", "span", "den Mann", "explanation", "Geben needs the dative.",
						"topic", "Dativ")),
				"correctedAnswer", "Ich gebe dem Mann das Buch.", "feedback", "Almost: mind the dative.")));

		answer(anna, id, "{\"text\":\"Ich gebe den Mann das Buch.\"}").andExpect(status().isCreated())
			.andExpect(jsonPath("$.evaluatedBy").value("AI"))
			.andExpect(jsonPath("$.score").value(0.7))
			.andExpect(jsonPath("$.feedback.errors[0].type").value("WRONG_CASE"))
			.andExpect(jsonPath("$.feedback.errors[0].grammarTopicId").value(dativ.toString()))
			.andExpect(jsonPath("$.feedback.correctedAnswer").value("Ich gebe dem Mann das Buch."))
			.andExpect(jsonPath("$.feedback.explanation").value("Almost: mind the dative."))
			.andExpect(jsonPath("$.mistakes[0].errorType").value("WRONG_CASE"))
			.andExpect(jsonPath("$.mistakes[0].excerpt").value("den Mann"));

		assertThat(jdbc.queryForObject("select feedback -> 'errors' -> 0 ->> 'type' from exercise_attempts",
				String.class))
			.isEqualTo("WRONG_CASE");
		assertThat(jdbc.queryForObject("select explanation from mistakes", String.class))
			.isEqualTo("Geben needs the dative.");
	}

	@Test
	void anAiFailureStoresNothing() throws Exception {
		UUID id = exercise(ExerciseSamples.of(ExerciseType.TRANSLATION));
		fake().failNext(new LlmException(LlmException.Kind.UNAVAILABLE, "overloaded"));

		answer(anna, id, "{\"text\":\"Ich gebe einem Kind Äpfel.\"}").andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));

		assertThat(count("exercise_attempts")).isZero();
		assertThat(events.stream(ExerciseAttemptedEvent.class)).isEmpty();
	}

	@Test
	void theAnswerMustFitTheExerciseType() throws Exception {
		UUID choice = exercise(ExerciseSamples.of(ExerciseType.MULTIPLE_CHOICE));
		answer(anna, choice, "{\"text\":\"der\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("index"));
		answer(anna, choice, "{\"index\":11}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("index"));
		UUID text = exercise(ExerciseSamples.of(ExerciseType.TRANSLATION));
		answer(anna, text, "{\"text\":\"" + "a".repeat(1001) + "\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("text"));
		answer(anna, text, "{\"text\":\"x\",\"durationMs\":-1}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("durationMs"));
		assertThat(count("exercise_attempts")).isZero();
	}

	@Test
	void attemptsAreListedNewestFirstAndOnlyForTheOwner() throws Exception {
		UUID id = exercise(ExerciseSamples.of(ExerciseType.MULTIPLE_CHOICE));
		answer(anna, id, "{\"index\":0}").andExpect(status().isCreated());
		answer(anna, id, "{\"index\":1}").andExpect(status().isCreated());

		mvc.perform(get("/api/exercises/{id}/attempts", id).header("Authorization", anna.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.content[0].correct").value(true))
			.andExpect(jsonPath("$.content[1].correct").value(false));
		mvc.perform(get("/api/exercises/{id}/attempts?sort=score", id).header("Authorization", anna.bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("sort"));

		OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.GET, "/api/exercises/{id}/attempts", id,
				anna.accessToken(), ben.accessToken());
		OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.POST, "/api/exercises/{id}/attempts", id,
				anna.accessToken(), ben.accessToken(), "{\"index\":1}");
	}

}
