package com.linguaflow.learning.practice;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.learning.practice.PracticeWeights.TopicStats;
import com.linguaflow.learning.practice.PracticeWeights.WordStats;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads what practice selection needs (PRC-01) in a few set-based queries: a read model
 * across the vocabulary, flashcard, exercise and learning tables, always scoped by user
 * and language. It only reads; each module still owns its writes.
 */
@Repository
class PracticeCandidates {

	/** How many of a topic's latest exercise attempts make up its score. */
	static final int RECENT_ATTEMPTS = 20;

	private final JdbcTemplate jdbc;

	PracticeCandidates(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The user's enriched words in a language, or only those of one study session.
	 * Card stats come from {@code srs_state}; attempts from exercises linked to the word.
	 */
	List<WordStats> words(UUID userId, String languageCode, @Nullable UUID studySessionId) {
		return jdbc.query("""
				select v.id, v.created_at, v.status,
				       min(s.ease_factor) as lowest_ease,
				       coalesce(max(s.lapses), 0) as max_lapses,
				       coalesce(bool_or(s.state in ('LEARNING', 'RELEARNING')), false) as learning,
				       max(s.last_reviewed_at) as last_reviewed_at,
				       min(s.due_at) as earliest_due_at,
				       (select max(a.attempted_at) from exercise_attempts a
				          join exercise_vocabulary ev on ev.exercise_id = a.exercise_id
				         where ev.vocabulary_item_id = v.id and a.user_id = v.user_id) as last_attempt_at,
				       (select p.weakness_score from vocabulary_progress p
				         where p.vocabulary_item_id = v.id) as analysed_weakness
				  from vocabulary_items v
				  left join flashcards f on f.vocabulary_item_id = v.id and f.user_id = v.user_id
				  left join srs_state s on s.flashcard_id = f.id and s.user_id = v.user_id
				 where v.user_id = ? and v.language_code = ? and v.ai_status = 'READY'
				   and (cast(? as uuid) is null or exists (
				        select 1 from session_vocabulary sv
				         where sv.vocabulary_item_id = v.id and sv.study_session_id = cast(? as uuid)))
				 group by v.id, v.created_at, v.status
				""", this::word, userId, languageCode, studySessionId, studySessionId);
	}

	/** The user's topics in a language, or only those of one study session. */
	List<TopicStats> topics(UUID userId, String languageCode, @Nullable UUID studySessionId) {
		return jdbc.query("""
				select t.id, t.created_at, t.status,
				       (select count(*) from exercise_attempts a
				          join exercise_grammar_topics eg on eg.exercise_id = a.exercise_id
				         where eg.grammar_topic_id = t.id and a.user_id = t.user_id) as attempts,
				       (select avg(recent.score) from (
				          select a.score from exercise_attempts a
				            join exercise_grammar_topics eg on eg.exercise_id = a.exercise_id
				           where eg.grammar_topic_id = t.id and a.user_id = t.user_id
				           order by a.attempted_at desc limit ?) recent) as recent_score,
				       (select max(a.attempted_at) from exercise_attempts a
				          join exercise_grammar_topics eg on eg.exercise_id = a.exercise_id
				         where eg.grammar_topic_id = t.id and a.user_id = t.user_id) as last_attempt_at,
				       (select p.weakness_score from grammar_topic_progress p
				         where p.grammar_topic_id = t.id) as analysed_weakness
				  from grammar_topics t
				 where t.user_id = ? and t.language_code = ?
				   and (cast(? as uuid) is null or exists (
				        select 1 from session_grammar sg
				         where sg.grammar_topic_id = t.id and sg.study_session_id = cast(? as uuid)))
				""", this::topic, RECENT_ATTEMPTS, userId, languageCode, studySessionId, studySessionId);
	}

	/** The user's most recent study session in a language: latest date, then latest created. */
	Optional<UUID> latestStudySession(UUID userId, String languageCode) {
		return jdbc
			.query("""
					select id from study_sessions where user_id = ? and language_code = ?
					 order by studied_on desc, created_at desc, id desc limit 1""",
					(rs, row) -> rs.getObject("id", UUID.class), userId, languageCode)
			.stream()
			.findFirst();
	}

	/** A lesson's ID, title and date. */
	record LessonInfo(UUID id, String title, LocalDate studiedOn) {
	}

	/** The latest study session, as {@link #latestStudySession} orders them, with its title. */
	Optional<LessonInfo> latestLesson(UUID userId, String languageCode) {
		return jdbc
			.query("""
					select id, title, studied_on from study_sessions where user_id = ? and language_code = ?
					 order by studied_on desc, created_at desc, id desc limit 1""",
					(rs, row) -> new LessonInfo(rs.getObject("id", UUID.class), rs.getString("title"),
							rs.getDate("studied_on").toLocalDate()),
					userId, languageCode)
			.stream()
			.findFirst();
	}

	/** Whether the study session is the user's, in that language. */
	boolean ownsStudySession(UUID userId, String languageCode, UUID studySessionId) {
		Integer found = jdbc.queryForObject(
				"select count(*) from study_sessions where id = ? and user_id = ? and language_code = ?", Integer.class,
				studySessionId, userId, languageCode);
		return found != null && found > 0;
	}

	/** A card that can be practised, with what decides which of a word's cards to show. */
	record CardOption(UUID id, UUID vocabularyItemId, CardDirection direction, @Nullable Instant dueAt) {
	}

	/**
	 * The reviewable cards of the given words (PRC-02): the user's, not suspended, in one
	 * of {@code directions}, and their word has a translation, as for the review queue.
	 */
	List<CardOption> cards(UUID userId, Collection<UUID> vocabularyItemIds, Collection<CardDirection> directions) {
		if (vocabularyItemIds.isEmpty() || directions.isEmpty()) {
			return List.of();
		}
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("userId", userId)
			.addValue("ids", vocabularyItemIds)
			.addValue("directions", directions.stream().map(Enum::name).toList());
		return new NamedParameterJdbcTemplate(jdbc).query("""
				select f.id, f.vocabulary_item_id, f.direction, s.due_at
				  from flashcards f
				  join srs_state s on s.flashcard_id = f.id and s.user_id = f.user_id
				  join vocabulary_items v on v.id = f.vocabulary_item_id and v.user_id = f.user_id
				 where f.user_id = :userId and f.vocabulary_item_id in (:ids) and not f.suspended
				   and f.direction in (:directions)
				   and v.translation is not null and trim(v.translation) <> ''
				""", params, (rs, n) -> new CardOption(rs.getObject("id", UUID.class),
				rs.getObject("vocabulary_item_id", UUID.class), CardDirection.valueOf(rs.getString("direction")),
				instant(rs, "due_at")));
	}

	private WordStats word(ResultSet rs, int row) throws SQLException {
		Double ease = rs.getBigDecimal("lowest_ease") == null ? null : rs.getBigDecimal("lowest_ease").doubleValue();
		return new WordStats(rs.getObject("id", UUID.class), instant(rs, "created_at"),
				KnowledgeStatus.valueOf(rs.getString("status")), ease, rs.getInt("max_lapses"),
				rs.getBoolean("learning"), instant(rs, "last_reviewed_at"), instant(rs, "earliest_due_at"),
				instant(rs, "last_attempt_at"), decimal(rs, "analysed_weakness"));
	}

	private TopicStats topic(ResultSet rs, int row) throws SQLException {
		Double score = rs.getBigDecimal("recent_score") == null ? null
				: rs.getBigDecimal("recent_score").doubleValue();
		return new TopicStats(rs.getObject("id", UUID.class), instant(rs, "created_at"),
				KnowledgeStatus.valueOf(rs.getString("status")), rs.getInt("attempts"), score,
				instant(rs, "last_attempt_at"), decimal(rs, "analysed_weakness"));
	}

	private static @Nullable Double decimal(ResultSet rs, String column) throws SQLException {
		return rs.getBigDecimal(column) == null ? null : rs.getBigDecimal(column).doubleValue();
	}

	private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

}
