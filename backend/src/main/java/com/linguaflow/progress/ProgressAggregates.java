package com.linguaflow.progress;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The progress tables (PRG-01): {@code grammar_topic_progress},
 * {@code vocabulary_progress} and {@code mistake_counts}. They are derived, so every
 * update recomputes the affected rows from the raw reviews, attempts and mistakes with
 * set-based SQL. That keeps the rolling windows exact, and a rebuild is the same queries
 * without the ID filter.
 * <ul>
 * <li><b>Topics:</b> attempts at exercises linked to the topic: totals, the average score
 * of the latest {@value #RECENT_ANSWERS} ({@code recent_accuracy}, partial credit
 * counting), distinct practice days in the learner's time zone and the last attempt.</li>
 * <li><b>Words:</b> flashcard reviews of the word's cards (right = any grade but AGAIN) and
 * attempts at exercises linked to it, together: answers, right answers, the share right
 * among the latest {@value #RECENT_ANSWERS}, the last answer, and the longest current
 * interval of its cards.</li>
 * <li><b>Weakness:</b> {@code 1 - recent_accuracy} for now; PRG-03 refines it.</li>
 * <li><b>Mistakes:</b> per learning language and error type, from {@code mistakes}.</li>
 * </ul>
 */
@Repository
public class ProgressAggregates {

	/** Answers in the rolling window: a topic's or word's latest ones. */
	public static final int RECENT_ANSWERS = 20;

	private final NamedParameterJdbcTemplate jdbc;

	private final Clock clock;

	ProgressAggregates(NamedParameterJdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/**
	 * Recomputes the user's rows for these topics; {@code null} means all of the user's.
	 * Topics without attempts lose their row.
	 */
	public void refreshTopics(UUID userId, @Nullable Collection<UUID> topicIds) {
		if (topicIds != null && topicIds.isEmpty()) {
			return;
		}
		String filter = topicIds == null ? "" : " and eg.grammar_topic_id in (:ids)";
		MapSqlParameterSource params = params(userId).addValue("ids", topicIds)
			.addValue("zone", zoneOf(userId));
		jdbc.update("""
				with answers as (
				  select eg.grammar_topic_id as topic_id, a.correct, a.score, a.attempted_at,
				         row_number() over (partition by eg.grammar_topic_id
				                            order by a.attempted_at desc, a.id desc) as rn
				    from exercise_attempts a
				    join exercise_grammar_topics eg on eg.exercise_id = a.exercise_id
				   where a.user_id = :userId""" + filter + "\n" + """
				)
				insert into grammar_topic_progress (grammar_topic_id, user_id, attempts_total, correct_total,
				       recent_accuracy, practice_days, last_practiced_at, weakness_score, updated_at)
				select x.topic_id, t.user_id, count(*), count(*) filter (where x.correct),
				       round(avg(x.score) filter (where x.rn <= :window), 3),
				       count(distinct cast(x.attempted_at at time zone :zone as date)),
				       max(x.attempted_at),
				       round(1 - avg(x.score) filter (where x.rn <= :window), 3),
				       :now
				  from answers x
				  join grammar_topics t on t.id = x.topic_id and t.user_id = :userId
				 group by x.topic_id, t.user_id
				on conflict (grammar_topic_id) do update set
				       attempts_total = excluded.attempts_total, correct_total = excluded.correct_total,
				       recent_accuracy = excluded.recent_accuracy, practice_days = excluded.practice_days,
				       last_practiced_at = excluded.last_practiced_at, weakness_score = excluded.weakness_score,
				       updated_at = excluded.updated_at
				""", params);
		jdbc.update("""
				delete from grammar_topic_progress p
				 where p.user_id = :userId""" + (topicIds == null ? "" : " and p.grammar_topic_id in (:ids)") + "\n" + """
				   and not exists (select 1 from exercise_attempts a
				                     join exercise_grammar_topics eg on eg.exercise_id = a.exercise_id
				                    where eg.grammar_topic_id = p.grammar_topic_id and a.user_id = :userId)
				""", params);
	}

	/**
	 * Recomputes the user's rows for these words; {@code null} means all of the user's.
	 * Words never answered lose their row.
	 */
	public void refreshWords(UUID userId, @Nullable Collection<UUID> vocabularyItemIds) {
		if (vocabularyItemIds != null && vocabularyItemIds.isEmpty()) {
			return;
		}
		boolean all = vocabularyItemIds == null;
		MapSqlParameterSource params = params(userId).addValue("ids", vocabularyItemIds);
		jdbc.update("""
				with answers as (
				  select f.vocabulary_item_id as item_id, r.grade <> 'AGAIN' as ok, r.reviewed_at as at, r.id
				    from flashcard_reviews r
				    join flashcards f on f.id = r.flashcard_id
				   where r.user_id = :userId""" + (all ? "" : " and f.vocabulary_item_id in (:ids)") + "\n" + """
				  union all
				  select ev.vocabulary_item_id, a.correct, a.attempted_at, a.id
				    from exercise_attempts a
				    join exercise_vocabulary ev on ev.exercise_id = a.exercise_id
				   where a.user_id = :userId""" + (all ? "" : " and ev.vocabulary_item_id in (:ids)") + "\n" + """
				), ranked as (
				  select answers.*, row_number() over (partition by item_id order by at desc, id desc) as rn
				    from answers
				)
				insert into vocabulary_progress (vocabulary_item_id, user_id, times_seen, times_correct,
				       recent_accuracy, interval_days, last_practiced_at, weakness_score, updated_at)
				select x.item_id, v.user_id, count(*), count(*) filter (where x.ok),
				       round(avg(cast(x.ok as int)) filter (where x.rn <= :window), 3),
				       coalesce((select max(s.interval_days) from flashcards f
				                   join srs_state s on s.flashcard_id = f.id
				                  where f.vocabulary_item_id = x.item_id), 0),
				       max(x.at),
				       round(1 - avg(cast(x.ok as int)) filter (where x.rn <= :window), 3),
				       :now
				  from ranked x
				  join vocabulary_items v on v.id = x.item_id and v.user_id = :userId
				 group by x.item_id, v.user_id
				on conflict (vocabulary_item_id) do update set
				       times_seen = excluded.times_seen, times_correct = excluded.times_correct,
				       recent_accuracy = excluded.recent_accuracy, interval_days = excluded.interval_days,
				       last_practiced_at = excluded.last_practiced_at, weakness_score = excluded.weakness_score,
				       updated_at = excluded.updated_at
				""", params);
		jdbc.update("""
				delete from vocabulary_progress p
				 where p.user_id = :userId""" + (all ? "" : " and p.vocabulary_item_id in (:ids)") + "\n" + """
				   and not exists (select 1 from flashcard_reviews r join flashcards f on f.id = r.flashcard_id
				                    where f.vocabulary_item_id = p.vocabulary_item_id and r.user_id = :userId)
				   and not exists (select 1 from exercise_attempts a
				                     join exercise_vocabulary ev on ev.exercise_id = a.exercise_id
				                    where ev.vocabulary_item_id = p.vocabulary_item_id and a.user_id = :userId)
				""", params);
	}

	/** Recomputes the user's mistake counters in a language; {@code null} means every language. */
	public void refreshMistakeCounts(UUID userId, @Nullable String languageCode) {
		String filter = languageCode == null ? "" : " and language_code = :language";
		MapSqlParameterSource params = params(userId).addValue("language", languageCode);
		jdbc.update("delete from mistake_counts where user_id = :userId" + filter, params);
		jdbc.update("""
				insert into mistake_counts (user_id, language_code, error_type, mistakes, last_seen_at, updated_at)
				select m.user_id, s.language_code, m.error_type, count(*), max(m.created_at), :now
				  from mistakes m
				  join exercise_attempts a on a.id = m.attempt_id
				  join exercises e on e.id = a.exercise_id
				  join exercise_sets s on s.id = e.exercise_set_id
				 where m.user_id = :userId""" + (languageCode == null ? "" : " and s.language_code = :language") + "\n" + """
				 group by m.user_id, s.language_code, m.error_type
				""", params);
	}

	/** Recomputes every progress row of one user. */
	public void rebuild(UUID userId) {
		refreshTopics(userId, null);
		refreshWords(userId, null);
		refreshMistakeCounts(userId, null);
	}

	/** Users with anything to aggregate. */
	public List<UUID> usersWithAnswers() {
		return jdbc.queryForList("""
				select user_id from flashcard_reviews union select user_id from exercise_attempts
				""", new MapSqlParameterSource(), UUID.class);
	}

	private MapSqlParameterSource params(UUID userId) {
		return new MapSqlParameterSource().addValue("userId", userId)
			.addValue("window", RECENT_ANSWERS)
			.addValue("now", Timestamp.from(clock.instant()));
	}

	/**
	 * The learner's time zone for PostgreSQL, which defines their practice days; profiles
	 * hold IANA names or "UTC" (USR-01), anything unreadable counts as UTC.
	 */
	private String zoneOf(UUID userId) {
		List<String> found = jdbc.queryForList("select timezone from users where id = :userId",
				new MapSqlParameterSource("userId", userId), String.class);
		try {
			ZoneId zone = ZoneId.of(found.isEmpty() ? "UTC" : found.getFirst());
			return zone instanceof ZoneOffset ? "UTC" : zone.getId();
		}
		catch (RuntimeException ex) {
			return "UTC";
		}
	}

}
