package com.linguaflow.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Reviews and attempts update the progress aggregates once committed (PRG-01). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ProgressEventsTests {

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

	private UUID hund;

	private UUID card;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		anna = new TestUsers(registration, accessTokens, jdbc).learner("anna@example.de");
		hund = UUID.randomUUID();
		jdbc.update("insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma, translation, "
				+ "ai_status) values (?, ?, 'de', 'der Hund', 'hund', 'dog', 'READY')", hund, anna.id());
		card = UUID.randomUUID();
		jdbc.update("insert into flashcards (id, user_id, vocabulary_item_id, direction) "
				+ "values (?, ?, ?, 'TARGET_TO_NATIVE')", card, anna.id(), hund);
		jdbc.update("insert into srs_state (flashcard_id, user_id) values (?, ?)", card, anna.id());
	}

	private void review(String grade) throws Exception {
		mvc.perform(post("/api/reviews").header("Authorization", anna.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"flashcardId":"%s","grade":"%s","reviewToken":"%s"}""".formatted(card, grade, UUID.randomUUID())))
			.andExpect(status().isCreated());
	}

	@Test
	void reviewsUpdateTheWord() throws Exception {
		review("AGAIN");
		review("GOOD");

		Map<String, Object> row = jdbc.queryForMap("select * from vocabulary_progress where vocabulary_item_id = ?",
				hund);
		assertThat(row.get("times_seen")).isEqualTo(2);
		assertThat(row.get("times_correct")).isEqualTo(1);
		assertThat((BigDecimal) row.get("recent_accuracy")).isEqualByComparingTo("0.5");
		assertThat(row.get("interval_days")).isEqualTo(jdbc.queryForObject(
				"select interval_days from srs_state where flashcard_id = ?", Integer.class, card));
	}

	@Test
	void attemptsUpdateTopicsWordsAndMistakeCounts() throws Exception {
		UUID dativ = UUID.randomUUID();
		jdbc.update("insert into grammar_topics (id, user_id, language_code, title, normalized_title) "
				+ "values (?, ?, 'de', 'Dativ', 'dativ')", dativ, anna.id());
		UUID set = UUID.randomUUID();
		jdbc.update("insert into exercise_sets (id, user_id, language_code, source, request, status) "
				+ "values (?, ?, 'de', 'MANUAL', '{\"count\":1}', 'READY')", set, anna.id());
		UUID exercise = UUID.randomUUID();
		jdbc.update("insert into exercises (id, exercise_set_id, user_id, position, type, prompt, solution) "
				+ "values (?, ?, ?, 0, 'MULTIPLE_CHOICE', ?::jsonb, ?::jsonb)", exercise, set, anna.id(),
				"{\"question\":\"Ich gebe ___ Hund Wasser.\",\"options\":[\"dem\",\"den\"]}", "{\"correctIndex\":0}");
		jdbc.update("insert into exercise_grammar_topics (exercise_id, grammar_topic_id) values (?, ?)", exercise,
				dativ);
		jdbc.update("insert into exercise_vocabulary (exercise_id, vocabulary_item_id) values (?, ?)", exercise,
				hund);

		for (int index : new int[] { 1, 0, 0 }) {
			mvc.perform(post("/api/exercises/{id}/attempts", exercise).header("Authorization", anna.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"index\":" + index + "}")).andExpect(status().isCreated());
		}

		Map<String, Object> topic = jdbc
			.queryForMap("select * from grammar_topic_progress where grammar_topic_id = ?", dativ);
		assertThat(topic.get("attempts_total")).isEqualTo(3);
		assertThat(topic.get("correct_total")).isEqualTo(2);
		assertThat(((BigDecimal) topic.get("recent_accuracy")).doubleValue()).isCloseTo(0.667, within(0.001));
		assertThat(topic.get("practice_days")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select times_seen from vocabulary_progress where vocabulary_item_id = ?",
				Integer.class, hund))
			.isEqualTo(3);
		assertThat(jdbc.queryForMap("select error_type, mistakes from mistake_counts where user_id = ?", anna.id()))
			.containsEntry("error_type", "WRONG_CHOICE")
			.containsEntry("mistakes", 1);
	}

}
