package com.linguaflow.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
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

/** Dashboard statistics through the API (PRG-05). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class StatisticsApiTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");

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

	private TestUser anna;

	private TestUser ben;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		TestUsers testUsers = new TestUsers(registration, accessTokens, jdbc);
		anna = testUsers.learner("anna@example.de");
		ben = testUsers.learner("ben@example.de");
		jdbc.update("update users set timezone = 'Asia/Almaty' where id = ?", anna.id());
	}

	private UUID word(String lemma, String status, String level) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma, translation, "
				+ "status, cefr_level, ai_status) values (?, ?, 'de', ?, ?, 'x', ?, ?, 'READY')", id, anna.id(), lemma,
				lemma.toLowerCase(), status, level);
		return id;
	}

	private void review(UUID word, String grade, String when, int durationMs) {
		UUID card = UUID.randomUUID();
		jdbc.update("insert into flashcards (id, user_id, vocabulary_item_id, direction) "
				+ "values (?, ?, ?, 'TARGET_TO_NATIVE') on conflict do nothing", card, anna.id(), word);
		UUID cardId = jdbc.queryForObject("select id from flashcards where vocabulary_item_id = ?", UUID.class, word);
		jdbc.update("insert into flashcard_reviews (id, flashcard_id, user_id, review_token, grade, reviewed_at, "
				+ "duration_ms, state_before, interval_before, interval_after, ease_before, ease_after) "
				+ "values (?, ?, ?, ?, ?, " + when + ", ?, 'NEW', 0, 0, 2.5, 2.5)", UUID.randomUUID(), cardId,
				anna.id(), UUID.randomUUID(), grade, durationMs);
	}

	private JsonNode stats(TestUser user, String query) throws Exception {
		return JSON.readTree(mvc.perform(get("/api/progress/stats" + query).header("Authorization", user.bearer()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString());
	}

	@Test
	void theDashboardFigures() throws Exception {
		UUID hund = word("Hund", "LEARNING", "A1");
		word("Katze", "LEARNED", "A1");
		word("Haus", "MASTERED", "A2");
		word("Ding", "NEW", null);
		jdbc.update("insert into grammar_topics (id, user_id, language_code, title, normalized_title, status, "
				+ "cefr_level) values (?, ?, 'de', 'Dativ', 'dativ', 'LEARNED', 'A2')", UUID.randomUUID(), anna.id());
		review(hund, "GOOD", "now()", 4000);
		review(hund, "AGAIN", "now()", 6000);
		review(hund, "GOOD", "now() - interval '1 day'", 3000);
		review(hund, "EASY", "now() - interval '3 days'", 2000);
		// Before the heatmap's 7 days
		review(hund, "GOOD", "now() - interval '30 days'", 1000);
		UUID set = UUID.randomUUID();
		jdbc.update("insert into exercise_sets (id, user_id, language_code, source, request, status) "
				+ "values (?, ?, 'de', 'MANUAL', '{\"count\":1}', 'READY')", set, anna.id());
		UUID exercise = UUID.randomUUID();
		jdbc.update("insert into exercises (id, exercise_set_id, user_id, position, type, prompt, solution) "
				+ "values (?, ?, ?, 0, 'TRANSLATION', '{}', '{}')", exercise, set, anna.id());
		jdbc.update("insert into exercise_attempts (id, exercise_id, user_id, answer, correct, score, evaluated_by, "
				+ "attempted_at, duration_ms) values (?, ?, ?, '{}', false, 0.5, 'RULES', now(), 20000)",
				UUID.randomUUID(), exercise, anna.id());

		JsonNode body = stats(anna, "?days=7&weeks=2");
		LocalDate today = LocalDate.now(ALMATY);
		assertThat(body.get("today").asString()).isEqualTo(today.toString());

		JsonNode words = body.get("words");
		assertThat(words.get("total").asInt()).isEqualTo(4);
		assertThat(words.get("byStatus").findValuesAsString("status")).containsExactly("NEW", "LEARNING", "LEARNED",
				"MASTERED");
		assertThat(words.get("byStatus").get(3).get("count").asInt()).isEqualTo(1);
		assertThat(words.get("byLevel")).hasSize(6);
		assertThat(words.get("byLevel").get(0).get("count").asInt()).isEqualTo(2);
		assertThat(words.get("byLevel").get(1).get("count").asInt()).isEqualTo(1);
		assertThat(words.get("unleveled").asInt()).isEqualTo(1);
		assertThat(body.get("grammarTopics").get("total").asInt()).isEqualTo(1);

		JsonNode activity = body.get("activity");
		assertThat(activity).hasSize(7);
		JsonNode last = activity.get(6);
		assertThat(last.get("date").asString()).isEqualTo(today.toString());
		assertThat(last.get("reviews").asInt()).isEqualTo(2);
		assertThat(last.get("remembered").asInt()).isEqualTo(1);
		assertThat(last.get("exercises").asInt()).isEqualTo(1);
		assertThat(last.get("studiedMs").asLong()).isEqualTo(30_000);
		assertThat(activity.get(5).get("reviews").asInt()).isEqualTo(1);
		assertThat(activity.get(4).get("reviews").asInt()).isZero();

		assertThat(body.get("accuracy")).hasSize(2);
		JsonNode thisWeek = body.get("accuracy").get(1);
		assertThat(thisWeek.get("exercises").asInt()).isEqualTo(1);
		assertThat(thisWeek.get("exerciseScore").asDouble()).isEqualTo(0.5);

		assertThat(body.get("currentStreak").asInt()).isEqualTo(2);
		assertThat(body.get("longestStreak").asInt()).isEqualTo(2);
		JsonNode time = body.get("timeStudied");
		assertThat(time.get("todayMs").asLong()).isEqualTo(30_000);
		assertThat(time.get("periodMs").asLong()).isEqualTo(35_000);
		assertThat(time.get("totalMs").asLong()).isEqualTo(36_000);

		// Ben's own figures are empty
		JsonNode bens = stats(ben, "");
		assertThat(bens.get("words").get("total").asInt()).isZero();
		assertThat(bens.get("activity")).hasSize(90);
		assertThat(bens.get("accuracy")).hasSize(12);
		assertThat(bens.get("currentStreak").asInt()).isZero();
	}

	@Test
	void parametersAreChecked() throws Exception {
		for (String query : new String[] { "?days=6", "?days=367", "?weeks=0", "?weeks=53" }) {
			mvc.perform(get("/api/progress/stats" + query).header("Authorization", anna.bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
	}

}
