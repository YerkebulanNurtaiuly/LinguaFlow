package com.linguaflow.learning.practice;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * A practice session's items ({@code practice_session_items}) and the answers made in it
 * (PRC-02). Items have no owner column; callers check the session is the user's first.
 * Positions are the primary key, so items are rewritten as a whole.
 */
@Repository
class PracticeItems {

	/** How many items a session has and how many are answered. */
	record Progress(int items, int answered) {
	}

	private final JdbcTemplate jdbc;

	private final NamedParameterJdbcTemplate named;

	PracticeItems(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
		this.named = new NamedParameterJdbcTemplate(jdbc);
	}

	List<PracticeItem> list(UUID sessionId) {
		return jdbc.query("""
				select position, item_type, flashcard_id, exercise_id, completed_at
				  from practice_session_items where practice_session_id = ? order by position
				""", (rs, n) -> new PracticeItem(rs.getInt("position"),
				PracticeItemType.valueOf(rs.getString("item_type")), rs.getObject("flashcard_id", UUID.class),
				rs.getObject("exercise_id", UUID.class), instant(rs.getTimestamp("completed_at"))), sessionId);
	}

	/** Replaces every item of the session. */
	void replace(UUID sessionId, List<PracticeItem> items) {
		jdbc.update("delete from practice_session_items where practice_session_id = ?", sessionId);
		jdbc.batchUpdate("""
				insert into practice_session_items
				       (practice_session_id, position, item_type, flashcard_id, exercise_id, completed_at)
				values (?, ?, ?, ?, ?, ?)
				""", items, 100, (ps, item) -> {
			ps.setObject(1, sessionId);
			ps.setShort(2, (short) item.position());
			ps.setString(3, item.type().name());
			ps.setObject(4, item.flashcardId());
			ps.setObject(5, item.exerciseId());
			ps.setTimestamp(6, item.completedAt() == null ? null : Timestamp.from(item.completedAt()));
		});
	}

	/**
	 * Marks the session's items for a card or exercise answered, keeping the first time.
	 * @return how many items show it; 0 when the session doesn't include it
	 */
	int markAnswered(UUID sessionId, PracticeItemType type, UUID refId, Instant at) {
		String column = type == PracticeItemType.FLASHCARD ? "flashcard_id" : "exercise_id";
		return jdbc.update("update practice_session_items set completed_at = coalesce(completed_at, ?) "
				+ "where practice_session_id = ? and " + column + " = ?", Timestamp.from(at), sessionId, refId);
	}

	Map<UUID, Progress> progress(Collection<UUID> sessionIds) {
		Map<UUID, Progress> progress = new HashMap<>();
		if (sessionIds.isEmpty()) {
			return progress;
		}
		named.query("""
				select practice_session_id, count(*) as items, count(completed_at) as answered
				  from practice_session_items where practice_session_id in (:ids)
				 group by practice_session_id
				""", new MapSqlParameterSource("ids", sessionIds),
				rs -> {
					progress.put(rs.getObject("practice_session_id", UUID.class),
							new Progress(rs.getInt("items"), rs.getInt("answered")));
				});
		return progress;
	}

	/** The session's reviews, oldest first. */
	List<PracticeSummary.Review> reviews(UUID userId, UUID sessionId) {
		return jdbc.query("""
				select flashcard_id, grade, duration_ms, reviewed_at from flashcard_reviews
				 where user_id = ? and practice_session_id = ? order by reviewed_at, id
				""", (rs, n) -> new PracticeSummary.Review(rs.getObject("flashcard_id", UUID.class),
				rs.getString("grade"), rs.getObject("duration_ms", Integer.class),
				rs.getTimestamp("reviewed_at").toInstant()), userId, sessionId);
	}

	/** The session's exercise attempts, oldest first. */
	List<PracticeSummary.Attempt> attempts(UUID userId, UUID sessionId) {
		return jdbc.query("""
				select exercise_id, correct, score, duration_ms, attempted_at from exercise_attempts
				 where user_id = ? and practice_session_id = ? order by attempted_at, id
				""", (rs, n) -> new PracticeSummary.Attempt(rs.getObject("exercise_id", UUID.class),
				rs.getBoolean("correct"), rs.getDouble("score"), rs.getObject("duration_ms", Integer.class),
				rs.getTimestamp("attempted_at").toInstant()), userId, sessionId);
	}

	private static @Nullable Instant instant(@Nullable Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

}
