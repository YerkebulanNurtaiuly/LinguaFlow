package com.linguaflow.progress;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.testsupport.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** The progress aggregates' arithmetic against PostgreSQL (PRG-01). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ProgressAggregatesTests {

	@Autowired
	private ProgressAggregates aggregates;

	@Autowired
	private JdbcTemplate jdbc;

	private UUID anna;

	@BeforeEach
	void createLearner() {
		anna = TestUsers.insertLearner(jdbc);
		// UTC+5: 20:00 UTC is already the next day for her
		jdbc.update("update users set timezone = 'Asia/Almaty' where id = ?", anna);
	}

	private UUID word(String lemma) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma, translation, "
				+ "ai_status) values (?, ?, 'de', ?, ?, 'x', 'READY')", id, anna, lemma, lemma.toLowerCase());
		return id;
	}

	private UUID card(UUID word, String direction, int intervalDays) {
		UUID card = UUID.randomUUID();
		jdbc.update("insert into flashcards (id, user_id, vocabulary_item_id, direction) values (?, ?, ?, ?)", card,
				anna, word, direction);
		jdbc.update("insert into srs_state (flashcard_id, user_id, state, interval_days, due_at) values (?, ?, ?, ?, "
				+ (intervalDays > 0 ? "now() + interval '1 day'" : "null") + ")", card, anna,
				intervalDays > 0 ? "REVIEW" : "NEW", intervalDays);
		return card;
	}

	private void review(UUID card, String grade, String at) {
		jdbc.update("insert into flashcard_reviews (id, flashcard_id, user_id, review_token, grade, reviewed_at, "
				+ "state_before, interval_before, interval_after, ease_before, ease_after) "
				+ "values (?, ?, ?, ?, ?, ?::timestamptz, 'NEW', 0, 0, 2.5, 2.5)", UUID.randomUUID(), card, anna,
				UUID.randomUUID(), grade, at);
	}

	private UUID topic(String title) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into grammar_topics (id, user_id, language_code, title, normalized_title) "
				+ "values (?, ?, 'de', ?, ?)", id, anna, title, title.toLowerCase());
		return id;
	}

	private UUID set;

	/** An attempt at a new exercise linked to {@code topic} (and {@code word}); returns the attempt. */
	private UUID attempt(UUID topic, UUID word, double score, String at) {
		if (set == null) {
			set = UUID.randomUUID();
			jdbc.update("insert into exercise_sets (id, user_id, language_code, source, request, status) "
					+ "values (?, ?, 'de', 'MANUAL', '{}', 'READY')", set, anna);
		}
		UUID exercise = UUID.randomUUID();
		Integer position = jdbc.queryForObject("select count(*) from exercises where exercise_set_id = ?",
				Integer.class, set);
		jdbc.update("insert into exercises (id, exercise_set_id, user_id, position, type, prompt, solution) "
				+ "values (?, ?, ?, ?, 'TRANSLATION', '{}', '{}')", exercise, set, anna, position);
		if (topic != null) {
			jdbc.update("insert into exercise_grammar_topics (exercise_id, grammar_topic_id) values (?, ?)", exercise,
					topic);
		}
		if (word != null) {
			jdbc.update("insert into exercise_vocabulary (exercise_id, vocabulary_item_id) values (?, ?)", exercise,
					word);
		}
		UUID id = UUID.randomUUID();
		jdbc.update("insert into exercise_attempts (id, exercise_id, user_id, answer, correct, score, evaluated_by, "
				+ "attempted_at) values (?, ?, ?, '{}', ?, ?, 'RULES', ?::timestamptz)", id, exercise, anna,
				score == 1, score, at);
		return id;
	}

	private void mistake(UUID attempt, String type, String at) {
		jdbc.update("insert into mistakes (id, attempt_id, user_id, error_type, created_at) "
				+ "values (?, ?, ?, ?, ?::timestamptz)", UUID.randomUUID(), attempt, anna, type, at);
	}

	private Map<String, Object> topicRow(UUID topic) {
		return jdbc.queryForMap("select * from grammar_topic_progress where grammar_topic_id = ?", topic);
	}

	private Map<String, Object> wordRow(UUID word) {
		return jdbc.queryForMap("select * from vocabulary_progress where vocabulary_item_id = ?", word);
	}

	@Test
	void topicsAverageTheirLatestTwentyAttemptsAndCountLocalDays() {
		UUID dativ = topic("Dativ");
		UUID perfekt = topic("Perfekt");
		// Two old misses that fall out of the window
		attempt(dativ, null, 0, "2026-10-01T10:00:00Z");
		attempt(dativ, null, 0, "2026-10-02T10:00:00Z");
		for (int i = 0; i < 20; i++) {
			// 15 right, 5 half right, on Nov 11 in Almaty: 20:00 UTC on Nov 10 and later
			attempt(dativ, null, i < 15 ? 1 : 0.5, i == 0 ? "2026-11-10T20:00:00Z" : "2026-11-11T10:%02d:00Z".formatted(i));
		}

		aggregates.refreshTopics(anna, List.of(dativ, perfekt));

		Map<String, Object> row = topicRow(dativ);
		assertThat(row.get("attempts_total")).isEqualTo(22);
		assertThat(row.get("correct_total")).isEqualTo(15);
		assertThat((BigDecimal) row.get("recent_accuracy")).isEqualByComparingTo("0.875");
		assertThat((BigDecimal) row.get("weakness_score")).isEqualByComparingTo("0.125");
		// Oct 1, Oct 2 and Nov 11 (in UTC it would be four days)
		assertThat(row.get("practice_days")).isEqualTo(3);
		assertThat(row.get("last_practiced_at").toString()).startsWith("2026-11-11");
		// Untried topics get no row
		assertThat(jdbc.queryForObject("select count(*) from grammar_topic_progress where grammar_topic_id = ?",
				Integer.class, perfekt))
			.isZero();
	}

	@Test
	void wordsCombineReviewsAndExerciseAttempts() {
		UUID hund = word("Hund");
		UUID recognition = card(hund, "TARGET_TO_NATIVE", 12);
		UUID recall = card(hund, "NATIVE_TO_TARGET", 3);
		review(recognition, "GOOD", "2026-11-09T10:00:00Z");
		review(recall, "AGAIN", "2026-11-10T10:00:00Z");
		review(recall, "HARD", "2026-11-11T10:00:00Z");
		attempt(topic("Dativ"), hund, 0.5, "2026-11-12T10:00:00Z");
		UUID katze = word("Katze");
		card(katze, "TARGET_TO_NATIVE", 0);

		aggregates.refreshWords(anna, List.of(hund, katze));

		Map<String, Object> row = wordRow(hund);
		assertThat(row.get("times_seen")).isEqualTo(4);
		// GOOD and HARD; AGAIN and the half-right attempt aren't
		assertThat(row.get("times_correct")).isEqualTo(2);
		assertThat((BigDecimal) row.get("recent_accuracy")).isEqualByComparingTo("0.5");
		assertThat(row.get("interval_days")).isEqualTo(12);
		assertThat(row.get("last_practiced_at").toString()).startsWith("2026-11-12");
		assertThat(jdbc.queryForObject("select count(*) from vocabulary_progress where vocabulary_item_id = ?",
				Integer.class, katze))
			.isZero();
	}

	@Test
	void mistakesAreCountedByType() {
		UUID dativ = topic("Dativ");
		UUID first = attempt(dativ, null, 0, "2026-11-10T10:00:00Z");
		mistake(first, "WRONG_CASE", "2026-11-10T10:00:00Z");
		mistake(first, "WRONG_ARTICLE", "2026-11-10T10:00:00Z");
		UUID second = attempt(dativ, null, 0.5, "2026-11-11T10:00:00Z");
		mistake(second, "WRONG_CASE", "2026-11-11T10:00:00Z");

		aggregates.refreshMistakeCounts(anna, "de");

		List<Map<String, Object>> counts = jdbc.queryForList(
				"select error_type, mistakes, last_seen_at from mistake_counts where user_id = ? order by error_type",
				anna);
		assertThat(counts).extracting(row -> row.get("error_type")).containsExactly("WRONG_ARTICLE", "WRONG_CASE");
		assertThat(counts).extracting(row -> row.get("mistakes")).containsExactly(1, 2);
		assertThat(counts.get(1).get("last_seen_at").toString()).startsWith("2026-11-11");
	}

	@Test
	void rowsWithoutAnswersAreRemovedAndARebuildMatches() {
		UUID dativ = topic("Dativ");
		UUID hund = word("Hund");
		attempt(dativ, hund, 1, "2026-11-10T10:00:00Z");
		aggregates.rebuild(anna);
		assertThat(topicRow(dativ).get("attempts_total")).isEqualTo(1);
		assertThat(wordRow(hund).get("times_seen")).isEqualTo(1);

		// The set is deleted with its attempts: the rows go too
		jdbc.update("delete from exercise_sets where user_id = ?", anna);
		aggregates.refreshTopics(anna, List.of(dativ));
		aggregates.refreshWords(anna, List.of(hund));
		assertThat(jdbc.queryForObject("select count(*) from grammar_topic_progress where user_id = ?",
				Integer.class, anna))
			.isZero();
		assertThat(jdbc.queryForObject("select count(*) from vocabulary_progress where user_id = ?", Integer.class,
				anna))
			.isZero();
		assertThat(aggregates.usersWithAnswers()).doesNotContain(anna);
	}

}
