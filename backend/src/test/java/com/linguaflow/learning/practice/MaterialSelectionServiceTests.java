package com.linguaflow.learning.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ErrorCode;
import com.linguaflow.common.error.NotFoundException;
import com.linguaflow.learning.practice.MaterialSelectionService.Request;
import com.linguaflow.learning.practice.MaterialSelector.Limits;
import com.linguaflow.learning.practice.PracticeWeights.TopicStats;
import com.linguaflow.learning.practice.PracticeWeights.WordStats;
import com.linguaflow.testsupport.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Practice selection against the real tables (PRC-01). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class MaterialSelectionServiceTests {

	@Autowired
	private MaterialSelectionService service;

	@Autowired
	private PracticeCandidates candidates;

	@Autowired
	private JdbcTemplate jdbc;

	private UUID anna;

	@BeforeEach
	void createLearner() {
		anna = TestUsers.insertLearner(jdbc);
	}

	private UUID word(UUID user, String lemma, String aiStatus) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma, translation, "
				+ "ai_status) values (?, ?, 'de', ?, ?, 'x', ?)", id, user, lemma, lemma.toLowerCase(), aiStatus);
		return id;
	}

	private void card(UUID user, UUID word, String direction, String state, String ease, int lapses,
			String lastReviewedSql, String dueSql) {
		UUID card = UUID.randomUUID();
		jdbc.update("insert into flashcards (id, user_id, vocabulary_item_id, direction) values (?, ?, ?, ?)", card,
				user, word, direction);
		jdbc.update("insert into srs_state (flashcard_id, user_id, state, ease_factor, lapses, last_reviewed_at, "
				+ "due_at) values (?, ?, ?, " + ease + ", ?, " + lastReviewedSql + ", " + dueSql + ")", card, user, state,
				lapses);
	}

	private UUID topic(UUID user, String title) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into grammar_topics (id, user_id, language_code, title, normalized_title) "
				+ "values (?, ?, 'de', ?, ?)", id, user, title, title.toLowerCase());
		return id;
	}

	private UUID lesson(UUID user, String date, List<UUID> words, List<UUID> topics) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into study_sessions (id, user_id, language_code, title, studied_on) "
				+ "values (?, ?, 'de', 'Lesson', ?::date)", id, user, date);
		words.forEach(w -> jdbc.update(
				"insert into session_vocabulary (study_session_id, vocabulary_item_id) values (?, ?)", id, w));
		topics.forEach(t -> jdbc.update(
				"insert into session_grammar (study_session_id, grammar_topic_id) values (?, ?)", id, t));
		return id;
	}

	/** An attempt at an exercise on {@code topic} (and {@code word}), {@code daysAgo} days ago. */
	private void attempt(UUID topic, UUID word, double score, int daysAgo) {
		UUID set = UUID.randomUUID();
		jdbc.update("insert into exercise_sets (id, user_id, language_code, source, request, status) "
				+ "values (?, ?, 'de', 'MANUAL', '{}', 'READY')", set, anna);
		UUID exercise = UUID.randomUUID();
		jdbc.update("insert into exercises (id, exercise_set_id, user_id, position, type, prompt, solution) "
				+ "values (?, ?, ?, 0, 'TRANSLATION', '{}', '{}')", exercise, set, anna);
		jdbc.update("insert into exercise_grammar_topics (exercise_id, grammar_topic_id) values (?, ?)", exercise,
				topic);
		if (word != null) {
			jdbc.update("insert into exercise_vocabulary (exercise_id, vocabulary_item_id) values (?, ?)", exercise,
					word);
		}
		jdbc.update("insert into exercise_attempts (id, exercise_id, user_id, answer, correct, score, evaluated_by, "
				+ "attempted_at) values (?, ?, ?, '{}', ?, ?, 'RULES', now() - make_interval(days => ?))",
				UUID.randomUUID(), exercise, anna, score == 1, score, daysAgo);
	}

	@Test
	void wordStatsCombineTheCardsAndExerciseAttempts() {
		UUID hund = word(anna, "Hund", "READY");
		card(anna, hund, "TARGET_TO_NATIVE", "REVIEW", "2.10", 1, "now() - interval '2 days'",
				"now() + interval '1 day'");
		card(anna, hund, "NATIVE_TO_TARGET", "RELEARNING", "1.70", 3, "now() - interval '1 day'",
				"now() - interval '1 hour'");
		UUID neu = word(anna, "neu", "READY");
		word(anna, "pending", "PENDING_AI");
		word(TestUsers.insertLearner(jdbc), "fremd", "READY");
		UUID dativ = topic(anna, "Dativ");
		attempt(dativ, neu, 0.5, 0);
		// The weak-point analysis's stored score (PRG-03)
		jdbc.update("insert into vocabulary_progress (vocabulary_item_id, user_id, times_seen, times_correct, "
				+ "weakness_score) values (?, ?, 2, 1, 0.77)", hund, anna);

		Map<UUID, WordStats> stats = candidates.words(anna, "de", null)
			.stream()
			.collect(Collectors.toMap(WordStats::id, Function.identity()));
		assertThat(stats).containsOnlyKeys(hund, neu);
		WordStats dog = stats.get(hund);
		assertThat(dog.lowestEase()).isEqualTo(1.70);
		assertThat(dog.maxLapses()).isEqualTo(3);
		assertThat(dog.learning()).isTrue();
		assertThat(dog.earliestDueAt()).isBefore(dog.lastReviewedAt().plusSeconds(86_400));
		assertThat(dog.analysedWeakness()).isEqualTo(0.77);
		assertThat(stats.get(neu).analysedWeakness()).isNull();
		assertThat(stats.get(neu).lastReviewedAt()).isNull();
		assertThat(stats.get(neu).lastAttemptAt()).isNotNull();
	}

	@Test
	void topicStatsAverageTheLatestAttempts() {
		UUID dativ = topic(anna, "Dativ");
		UUID perfekt = topic(anna, "Perfekt");
		// 22 attempts; only the latest 20 count: two old zeros fall out
		attempt(dativ, null, 0, 40);
		attempt(dativ, null, 0, 39);
		for (int i = 0; i < 20; i++) {
			attempt(dativ, null, i < 15 ? 1 : 0.5, i);
		}

		Map<UUID, TopicStats> stats = candidates.topics(anna, "de", null)
			.stream()
			.collect(Collectors.toMap(TopicStats::id, Function.identity()));
		assertThat(stats.get(dativ).attempts()).isEqualTo(22);
		assertThat(stats.get(dativ).recentScore()).isCloseTo(0.875, within(1e-9));
		assertThat(stats.get(perfekt).attempts()).isZero();
		assertThat(stats.get(perfekt).recentScore()).isNull();
	}

	@Test
	void theCurrentLessonIsTheLatestStudySession() {
		UUID old = word(anna, "alt", "READY");
		UUID hund = word(anna, "Hund", "READY");
		UUID katze = word(anna, "Katze", "READY");
		word(anna, "elsewhere", "READY");
		UUID dativ = topic(anna, "Dativ");
		UUID perfekt = topic(anna, "Perfekt");
		UUID first = lesson(anna, "2026-11-01", List.of(old), List.of(perfekt));
		UUID latest = lesson(anna, "2026-11-10", List.of(hund, katze), List.of(dativ));

		var result = service.select(anna,
				new Request(PracticeMode.CURRENT_LESSON, null, new Limits(10, 5, 0.6), 7));
		assertThat(result.studySessionId()).isEqualTo(latest);
		assertThat(result.vocabularyItemIds()).containsExactlyInAnyOrder(hund, katze);
		assertThat(result.grammarTopicIds()).containsExactly(dativ);

		var chosen = service.select(anna, new Request(PracticeMode.CURRENT_LESSON, first, new Limits(10, 5, 0.6), 7));
		assertThat(chosen.vocabularyItemIds()).containsExactly(old);
		assertThat(chosen.grammarTopicIds()).containsExactly(perfekt);
	}

	@Test
	void aLessonMustExistAndBeTheLearners() {
		assertThatThrownBy(() -> service.select(anna,
				new Request(PracticeMode.CURRENT_LESSON, null, new Limits(10, 5, 0.6), 7)))
			.isInstanceOf(ApiException.class)
			.satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(ErrorCode.NO_STUDY_SESSION));
		UUID bens = lesson(TestUsers.insertLearner(jdbc), "2026-11-10", List.of(), List.of());
		assertThatThrownBy(() -> service.select(anna,
				new Request(PracticeMode.CURRENT_LESSON, bens, new Limits(10, 5, 0.6), 7)))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void selectionIsReproducibleGivenASeed() {
		for (int i = 0; i < 30; i++) {
			word(anna, "wort" + i, "READY");
		}
		for (int i = 0; i < 8; i++) {
			topic(anna, "Thema " + i);
		}
		for (PracticeMode mode : List.of(PracticeMode.MIXED, PracticeMode.FULL_REVISION, PracticeMode.WEAK_SPOTS)) {
			Request request = new Request(mode, null, new Limits(10, 3, 0.6), 1234);
			var first = service.select(anna, request);
			assertThat(service.select(anna, request)).isEqualTo(first);
			assertThat(first.vocabularyItemIds()).hasSize(10).doesNotHaveDuplicates();
			assertThat(first.grammarTopicIds()).hasSize(3);
			assertThat(first.studySessionId()).isNull();
		}
	}

}
