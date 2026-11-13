package com.linguaflow.progress;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.progress.WeaknessScores.TopicInput;
import com.linguaflow.progress.WeaknessScores.Weakness;
import com.linguaflow.progress.WeaknessScores.WordInput;
import com.linguaflow.user.ActiveLanguageService;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Weak-point analysis (PRG-03): scores topics and words with {@link WeaknessScores},
 * stores the score in {@code weakness_score} after each progress update (practice
 * selection, PRC-01, reads it), and lists the weakest with their reasons, scored fresh.
 */
@Service
public class WeakPointService {

	/** At most this many of each kind per request. */
	public static final int MAX_LIMIT = 50;

	/** Below this score nothing counts as a weak point. */
	public static final double MIN_SCORE = 0.2;

	/** A topic or word with its score and reasons. */
	public record WeakPoint(UUID id, String name, @Nullable String translation, KnowledgeStatus status,
			int answers, @Nullable Double recentAccuracy, @Nullable Instant lastPracticedAt, Weakness weakness) {
	}

	/** The weakest topics and words of the active language, weakest first. */
	public record WeakPoints(List<WeakPoint> topics, List<WeakPoint> words) {
	}

	private final NamedParameterJdbcTemplate jdbc;

	private final ActiveLanguageService activeLanguages;

	private final Clock clock;

	WeakPointService(NamedParameterJdbcTemplate jdbc, ActiveLanguageService activeLanguages, Clock clock) {
		this.jdbc = jdbc;
		this.activeLanguages = activeLanguages;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public WeakPoints weakPoints(UUID userId, int limit) {
		String language = activeLanguages.requireActive(userId);
		return new WeakPoints(weakest(topics(userId, language, null), limit),
				weakest(words(userId, language, null), limit));
	}

	private static List<WeakPoint> weakest(List<WeakPoint> points, int limit) {
		return points.stream()
			.filter(point -> point.weakness().score() >= MIN_SCORE)
			.sorted(Comparator.comparingDouble((WeakPoint point) -> point.weakness().score())
				.reversed()
				.thenComparing(WeakPoint::id))
			.limit(limit)
			.toList();
	}

	/**
	 * Stores fresh scores for the user's topics and words with progress; {@code null}
	 * means all of them, an empty collection none. Runs in the caller's transaction.
	 */
	@Transactional
	public void updateScores(UUID userId, @Nullable Collection<UUID> topicIds, @Nullable Collection<UUID> wordIds) {
		if (topicIds == null || !topicIds.isEmpty()) {
			List<WeakPoint> topics = topics(userId, null, topicIds);
			jdbc.batchUpdate("update grammar_topic_progress set weakness_score = :score where grammar_topic_id = :id",
					topics.stream().map(WeakPointService::scoreParams).toArray(MapSqlParameterSource[]::new));
		}
		if (wordIds == null || !wordIds.isEmpty()) {
			List<WeakPoint> words = words(userId, null, wordIds);
			jdbc.batchUpdate(
					"update vocabulary_progress set weakness_score = :score where vocabulary_item_id = :id",
					words.stream().map(WeakPointService::scoreParams).toArray(MapSqlParameterSource[]::new));
		}
	}

	private static MapSqlParameterSource scoreParams(WeakPoint point) {
		return new MapSqlParameterSource("id", point.id()).addValue("score", point.weakness().score());
	}

	/** The user's topics with progress, in a language and/or among IDs. */
	private List<WeakPoint> topics(UUID userId, @Nullable String language, @Nullable Collection<UUID> ids) {
		Instant now = clock.instant();
		return jdbc.query("""
				select t.id, t.title, t.status, p.attempts_total, p.recent_accuracy, p.last_practiced_at,
				       m.error_type as top_mistake, coalesce(m.mistakes, 0) as top_mistake_count
				  from grammar_topic_progress p
				  join grammar_topics t on t.id = p.grammar_topic_id
				  left join lateral (
				       select error_type, count(*) as mistakes from mistakes
				        where grammar_topic_id = t.id and user_id = :userId
				        group by error_type order by count(*) desc, error_type limit 1) m on true
				 where p.user_id = :userId""" + filters("t", language, ids),
				params(userId, language, ids), (rs, n) -> {
					TopicInput input = new TopicInput(status(rs), rs.getInt("attempts_total"), accuracy(rs),
							instant(rs, "last_practiced_at"), rs.getString("top_mistake"),
							rs.getInt("top_mistake_count"));
					return new WeakPoint(rs.getObject("id", UUID.class), rs.getString("title"), null, input.status(),
							input.answers(), input.recentAccuracy(), input.lastPracticedAt(),
							WeaknessScores.topic(input, now));
				});
	}

	/** The user's words with progress, in a language and/or among IDs. */
	private List<WeakPoint> words(UUID userId, @Nullable String language, @Nullable Collection<UUID> ids) {
		Instant now = clock.instant();
		return jdbc.query("""
				select t.id, t.lemma, t.translation, t.status, p.times_seen, p.recent_accuracy, p.last_practiced_at,
				       coalesce((select max(s.lapses) from flashcards f
				                   join srs_state s on s.flashcard_id = f.id
				                  where f.vocabulary_item_id = t.id), 0) as lapses
				  from vocabulary_progress p
				  join vocabulary_items t on t.id = p.vocabulary_item_id
				 where p.user_id = :userId""" + filters("t", language, ids),
				params(userId, language, ids), (rs, n) -> {
					WordInput input = new WordInput(status(rs), rs.getInt("times_seen"), accuracy(rs),
							rs.getInt("lapses"), instant(rs, "last_practiced_at"));
					return new WeakPoint(rs.getObject("id", UUID.class), rs.getString("lemma"),
							rs.getString("translation"), input.status(), input.answers(), input.recentAccuracy(),
							input.lastPracticedAt(), WeaknessScores.word(input, now));
				});
	}

	private static String filters(String alias, @Nullable String language, @Nullable Collection<UUID> ids) {
		return (language == null ? "" : " and " + alias + ".language_code = :language")
				+ (ids == null ? "" : " and " + alias + ".id in (:ids)");
	}

	private static MapSqlParameterSource params(UUID userId, @Nullable String language,
			@Nullable Collection<UUID> ids) {
		return new MapSqlParameterSource("userId", userId).addValue("language", language).addValue("ids", ids);
	}

	private static KnowledgeStatus status(ResultSet rs) throws SQLException {
		return KnowledgeStatus.valueOf(rs.getString("status"));
	}

	private static @Nullable Double accuracy(ResultSet rs) throws SQLException {
		return rs.getBigDecimal("recent_accuracy") == null ? null : rs.getBigDecimal("recent_accuracy").doubleValue();
	}

	private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

}
