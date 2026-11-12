package com.linguaflow.progress;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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

/** Mastery rules against the real tables and services (PRG-02). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class MasteryServiceTests {

	@Autowired
	private MasteryService mastery;

	@Autowired
	private ProgressAggregates aggregates;

	@Autowired
	private JdbcTemplate jdbc;

	private UUID anna;

	private UUID set;

	@BeforeEach
	void createLearner() {
		anna = TestUsers.insertLearner(jdbc);
		set = UUID.randomUUID();
		jdbc.update("insert into exercise_sets (id, user_id, language_code, source, request, status) "
				+ "values (?, ?, 'de', 'MANUAL', '{\"count\":1}', 'READY')", set, anna);
	}

	private UUID topic(String status) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into grammar_topics (id, user_id, language_code, title, normalized_title, status) "
				+ "values (?, ?, 'de', ?, ?, ?)", id, anna, "Topic " + id, "topic " + id, status);
		return id;
	}

	private void attempts(UUID topic, int count, double score, String day) {
		for (int i = 0; i < count; i++) {
			UUID exercise = UUID.randomUUID();
			Integer position = jdbc.queryForObject("select count(*) from exercises where exercise_set_id = ?",
					Integer.class, set);
			jdbc.update("insert into exercises (id, exercise_set_id, user_id, position, type, prompt, solution) "
					+ "values (?, ?, ?, ?, 'TRANSLATION', '{}', '{}')", exercise, set, anna, position);
			jdbc.update("insert into exercise_grammar_topics (exercise_id, grammar_topic_id) values (?, ?)",
					exercise, topic);
			jdbc.update("insert into exercise_attempts (id, exercise_id, user_id, answer, correct, score, "
					+ "evaluated_by, attempted_at) values (?, ?, ?, '{}', ?, ?, 'RULES', ?::timestamptz)",
					UUID.randomUUID(), exercise, anna, score == 1, score, day + "T10:" + "%02d".formatted(i) + ":00Z");
		}
	}

	private String status(String table, UUID id) {
		return jdbc.queryForObject("select status from " + table + " where id = ?", String.class, id);
	}

	@Test
	void topicsEarnMasteryAndLoseItAgain() {
		UUID dativ = topic("LEARNING");
		attempts(dativ, 7, 1, "2026-11-01");
		attempts(dativ, 7, 1, "2026-11-02");
		attempts(dativ, 5, 1, "2026-11-03");
		aggregates.refreshTopics(anna, List.of(dativ));
		// 19 attempts: not yet
		assertThat(mastery.apply(anna, List.of(dativ), List.of())).isEqualTo(new MasteryService.Changes(0, 0));

		attempts(dativ, 1, 0.5, "2026-11-04");
		aggregates.refreshTopics(anna, List.of(dativ));
		// 20 attempts on 4 days, 0.975 on average
		assertThat(mastery.apply(anna, List.of(dativ), List.of())).isEqualTo(new MasteryService.Changes(1, 0));
		assertThat(status("grammar_topics", dativ)).isEqualTo("MASTERED");
		// Applying again changes nothing
		assertThat(mastery.apply(anna, null, null)).isEqualTo(new MasteryService.Changes(0, 0));

		// A bad run: the latest 20 drop below 70%
		attempts(dativ, 8, 0, "2026-11-10");
		aggregates.refreshTopics(anna, List.of(dativ));
		mastery.apply(anna, null, null);
		assertThat(status("grammar_topics", dativ)).isEqualTo("LEARNED");

		assertThat(jdbc.queryForList("select reason from grammar_topic_status_history where grammar_topic_id = ? "
				+ "order by changed_at, id", String.class, dativ))
			.containsExactly("MASTERY_RULE", "DEMOTION_RULE");
	}

	@Test
	void wordsFollowTheirCardsInterval() {
		UUID hund = UUID.randomUUID();
		jdbc.update("insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma, translation, "
				+ "ai_status) values (?, ?, 'de', 'Hund', 'hund', 'dog', 'READY')", hund, anna);
		UUID card = UUID.randomUUID();
		jdbc.update("insert into flashcards (id, user_id, vocabulary_item_id, direction) "
				+ "values (?, ?, ?, 'TARGET_TO_NATIVE')", card, anna, hund);
		jdbc.update("insert into srs_state (flashcard_id, user_id, state, interval_days, due_at) "
				+ "values (?, ?, 'REVIEW', 25, now() + interval '25 days')", card, anna);
		jdbc.update("insert into flashcard_reviews (id, flashcard_id, user_id, review_token, grade, reviewed_at, "
				+ "state_before, interval_before, interval_after, ease_before, ease_after) "
				+ "values (?, ?, ?, ?, 'GOOD', now(), 'REVIEW', 10, 25, 2.5, 2.5)", UUID.randomUUID(), card, anna,
				UUID.randomUUID());

		aggregates.refreshWords(anna, List.of(hund));
		assertThat(mastery.apply(anna, List.of(), List.of(hund))).isEqualTo(new MasteryService.Changes(0, 1));
		assertThat(status("vocabulary_items", hund)).isEqualTo("MASTERED");

		// A lapse sends the card back to a short interval
		jdbc.update("update srs_state set state = 'RELEARNING', interval_days = 1 where flashcard_id = ?", card);
		aggregates.refreshWords(anna, List.of(hund));
		mastery.apply(anna, null, null);
		assertThat(status("vocabulary_items", hund)).isEqualTo("LEARNED");
	}

}
