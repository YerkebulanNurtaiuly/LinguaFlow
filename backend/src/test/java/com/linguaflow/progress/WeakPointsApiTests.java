package com.linguaflow.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.testsupport.TestUsers;
import com.linguaflow.testsupport.TestUsers.TestUser;
import com.linguaflow.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Weak-point analysis through the API (PRG-03). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class WeakPointsApiTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private UserRepository users;

	@Autowired
	private RegistrationService registration;

	@Autowired
	private AccessTokenService accessTokens;

	@Autowired
	private ProgressAggregates aggregates;

	@Autowired
	private WeakPointService weakPoints;

	private TestUser anna;

	private TestUser ben;

	private UUID set;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		TestUsers testUsers = new TestUsers(registration, accessTokens, jdbc);
		anna = testUsers.learner("anna@example.de");
		ben = testUsers.learner("ben@example.de");
		set = UUID.randomUUID();
		jdbc.update("insert into exercise_sets (id, user_id, language_code, source, request, status) "
				+ "values (?, ?, 'de', 'MANUAL', '{\"count\":1}', 'READY')", set, anna.id());
	}

	private UUID topic(String title) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into grammar_topics (id, user_id, language_code, title, normalized_title, status) "
				+ "values (?, ?, 'de', ?, ?, 'LEARNING')", id, anna.id(), title, title.toLowerCase());
		return id;
	}

	/** {@code count} attempts at new exercises on the topic, the wrong ones with a mistake. */
	private void attempts(UUID topic, int count, boolean correct, String mistake) {
		for (int i = 0; i < count; i++) {
			UUID exercise = UUID.randomUUID();
			Integer position = jdbc.queryForObject("select count(*) from exercises where exercise_set_id = ?",
					Integer.class, set);
			jdbc.update("insert into exercises (id, exercise_set_id, user_id, position, type, prompt, solution) "
					+ "values (?, ?, ?, ?, 'TRANSLATION', '{}', '{}')", exercise, set, anna.id(), position);
			jdbc.update("insert into exercise_grammar_topics (exercise_id, grammar_topic_id) values (?, ?)",
					exercise, topic);
			UUID attempt = UUID.randomUUID();
			jdbc.update("insert into exercise_attempts (id, exercise_id, user_id, answer, correct, score, "
					+ "evaluated_by, attempted_at) values (?, ?, ?, '{}', ?, ?, 'RULES', now())", attempt, exercise,
					anna.id(), correct, correct ? 1 : 0);
			if (!correct) {
				jdbc.update("insert into mistakes (id, attempt_id, user_id, grammar_topic_id, error_type) "
						+ "values (?, ?, ?, ?, ?)", UUID.randomUUID(), attempt, anna.id(), topic, mistake);
			}
		}
	}

	private UUID word(String lemma, String translation, int lapses, String... grades) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma, translation, "
				+ "ai_status) values (?, ?, 'de', ?, ?, ?, 'READY')", id, anna.id(), lemma, lemma.toLowerCase(),
				translation);
		UUID card = UUID.randomUUID();
		jdbc.update("insert into flashcards (id, user_id, vocabulary_item_id, direction) "
				+ "values (?, ?, ?, 'TARGET_TO_NATIVE')", card, anna.id(), id);
		jdbc.update("insert into srs_state (flashcard_id, user_id, state, interval_days, lapses, due_at) "
				+ "values (?, ?, 'REVIEW', 1, ?, now() + interval '1 day')", card, anna.id(), lapses);
		for (String grade : grades) {
			jdbc.update("insert into flashcard_reviews (id, flashcard_id, user_id, review_token, grade, reviewed_at, "
					+ "state_before, interval_before, interval_after, ease_before, ease_after) "
					+ "values (?, ?, ?, ?, ?, now(), 'REVIEW', 1, 1, 2.5, 2.5)", UUID.randomUUID(), card, anna.id(),
					UUID.randomUUID(), grade);
		}
		return id;
	}

	private JsonNode weakPointsOf(TestUser user, String query) throws Exception {
		return JSON.readTree(mvc.perform(get("/api/progress/weak-points" + query).header("Authorization",
				user.bearer())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	@Test
	void theWeakestComeFirstWithTheirReasons() throws Exception {
		UUID dativ = topic("Dativ");
		attempts(dativ, 6, false, "WRONG_CASE");
		attempts(dativ, 4, true, null);
		UUID perfekt = topic("Perfekt");
		attempts(perfekt, 20, true, null);
		UUID akkusativ = topic("Akkusativ");
		attempts(akkusativ, 5, false, "WRONG_ARTICLE");
		UUID hund = word("der Hund", "dog", 3, "AGAIN", "GOOD", "AGAIN");
		word("die Katze", "cat", 0, "GOOD", "GOOD", "EASY", "GOOD");
		aggregates.rebuild(anna.id());
		weakPoints.updateScores(anna.id(), null, null);

		JsonNode body = weakPointsOf(anna, "");
		JsonNode topics = body.get("grammarTopics");
		// The solid Perfekt isn't listed; the all-wrong Akkusativ is weakest
		assertThat(topics).hasSize(2);
		assertThat(topics.get(0).get("name").asString()).isEqualTo("Akkusativ");
		JsonNode first = topics.get(1);
		assertThat(first.get("name").asString()).isEqualTo("Dativ");
		assertThat(first.get("answers").asInt()).isEqualTo(10);
		assertThat(first.get("recentAccuracy").asDouble()).isEqualTo(0.4);
		assertThat(first.get("reasons").get(0).get("code").asString()).isEqualTo("LOW_ACCURACY");
		assertThat(first.get("reasons").get(1).get("code").asString()).isEqualTo("FREQUENT_MISTAKE");
		assertThat(first.get("reasons").get(1).get("errorType").asString()).isEqualTo("WRONG_CASE");
		assertThat(first.get("reasons").get(1).get("value").asInt()).isEqualTo(6);

		JsonNode words = body.get("words");
		assertThat(words).hasSize(1);
		assertThat(words.get(0).get("id").asString()).isEqualTo(hund.toString());
		assertThat(words.get(0).get("translation").asString()).isEqualTo("dog");
		assertThat(words.get(0).get("reasons").findValuesAsString("code")).contains("LOW_ACCURACY", "LAPSES");

		// The score is stored for practice selection (PRC-01)
		BigDecimal stored = jdbc.queryForObject(
				"select weakness_score from grammar_topic_progress where grammar_topic_id = ?", BigDecimal.class,
				dativ);
		assertThat(stored.doubleValue()).isEqualTo(first.get("score").asDouble());
		BigDecimal solid = jdbc.queryForObject(
				"select weakness_score from grammar_topic_progress where grammar_topic_id = ?", BigDecimal.class,
				perfekt);
		assertThat(solid.doubleValue()).isLessThan(WeakPointService.MIN_SCORE);

		assertThat(weakPointsOf(anna, "?limit=1").get("grammarTopics")).hasSize(1);
		// Ben has no practice of his own, and Anna's isn't his
		JsonNode bens = weakPointsOf(ben, "");
		assertThat(bens.get("grammarTopics")).isEmpty();
		assertThat(bens.get("words")).isEmpty();
	}

	@Test
	void theLimitIsChecked() throws Exception {
		mvc.perform(get("/api/progress/weak-points?limit=0").header("Authorization", anna.bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("limit"));
		mvc.perform(get("/api/progress/weak-points?limit=51").header("Authorization", anna.bearer()))
			.andExpect(status().isBadRequest());
	}

}
