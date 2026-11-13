package com.linguaflow.progress;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.progress.ActivityStats.Day;
import com.linguaflow.progress.ActivityStats.Week;
import com.linguaflow.user.ActiveLanguageService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dashboard statistics (PRG-05) for the active language, on the learner's calendar
 * ({@code users.timezone}): material by status and CEFR level, practice per day (reviews
 * and exercise attempts), the weekly accuracy trend, the practice streak and time studied.
 */
@Service
public class StatisticsService {

	public static final int MIN_DAYS = 7;

	public static final int MAX_DAYS = 366;

	public static final int MIN_WEEKS = 1;

	public static final int MAX_WEEKS = 52;

	/** How much material is in each status and level; {@code unleveled} has no CEFR level. */
	public record Material(int total, Map<KnowledgeStatus, Integer> byStatus, Map<CefrLevel, Integer> byLevel,
			int unleveled) {
	}

	/** @param periodMs answering time over the heatmap's days */
	public record TimeStudied(long todayMs, long periodMs, long totalMs) {
	}

	/**
	 * @param currentStreak days in a row with a review or exercise, ending today, or
	 * yesterday while today has none yet
	 */
	public record Statistics(LocalDate today, Material words, Material grammarTopics, LocalDate from,
			List<Day> activity, List<Week> accuracy, int currentStreak, int longestStreak, TimeStudied timeStudied) {
	}

	private final NamedParameterJdbcTemplate jdbc;

	private final ActiveLanguageService activeLanguages;

	private final Clock clock;

	StatisticsService(NamedParameterJdbcTemplate jdbc, ActiveLanguageService activeLanguages, Clock clock) {
		this.jdbc = jdbc;
		this.activeLanguages = activeLanguages;
		this.clock = clock;
	}

	/**
	 * @param days the heatmap's days, today included
	 * @param weeks the accuracy trend's weeks, this one included
	 */
	@Transactional(readOnly = true)
	public Statistics statistics(UUID userId, int days, int weeks) {
		String language = activeLanguages.requireActive(userId);
		ZoneId zone = zoneOf(userId);
		LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
		MapSqlParameterSource params = new MapSqlParameterSource("userId", userId).addValue("language", language)
			.addValue("zone", zone instanceof ZoneOffset ? "UTC" : zone.getId());

		List<Day> all = activity(params);
		LocalDate from = today.minusDays(days - 1L);
		List<Day> heatmap = ActivityStats.daysBetween(all, from, today);
		long total = all.stream().mapToLong(Day::studiedMs).sum();
		long period = heatmap.stream().mapToLong(Day::studiedMs).sum();
		long todayMs = heatmap.getLast().studiedMs();

		return new Statistics(today, material("vocabulary_items", params), material("grammar_topics", params), from,
				heatmap, ActivityStats.weeks(all, today, weeks), ActivityStats.currentStreak(all, today),
				ActivityStats.longestStreak(all), new TimeStudied(todayMs, period, total));
	}

	/** Every day with practice in the language, on the learner's calendar. */
	private List<Day> activity(MapSqlParameterSource params) {
		return jdbc.query("""
				select day, sum(reviews) as reviews, sum(remembered) as remembered, sum(exercises) as exercises,
				       sum(score_sum) as score_sum, sum(studied_ms) as studied_ms
				  from (
				    select cast(r.reviewed_at at time zone :zone as date) as day, count(*) as reviews,
				           count(*) filter (where r.grade <> 'AGAIN') as remembered, 0 as exercises,
				           0 as score_sum, coalesce(sum(r.duration_ms), 0) as studied_ms
				      from flashcard_reviews r
				      join flashcards f on f.id = r.flashcard_id
				      join vocabulary_items v on v.id = f.vocabulary_item_id
				     where r.user_id = :userId and v.language_code = :language
				     group by 1
				    union all
				    select cast(a.attempted_at at time zone :zone as date), 0, 0, count(*), sum(a.score),
				           coalesce(sum(a.duration_ms), 0)
				      from exercise_attempts a
				      join exercises e on e.id = a.exercise_id
				      join exercise_sets s on s.id = e.exercise_set_id
				     where a.user_id = :userId and s.language_code = :language
				     group by 1
				  ) days
				 group by day
				 order by day
				""", params, (rs, n) -> new Day(rs.getDate("day").toLocalDate(), rs.getInt("reviews"),
				rs.getInt("remembered"), rs.getInt("exercises"), rs.getDouble("score_sum"), rs.getLong("studied_ms")));
	}

	/** Counts by status and level; {@code table} is one of two constants, never input. */
	private Material material(String table, MapSqlParameterSource params) {
		Map<KnowledgeStatus, Integer> byStatus = new EnumMap<>(KnowledgeStatus.class);
		for (KnowledgeStatus status : KnowledgeStatus.values()) {
			byStatus.put(status, 0);
		}
		Map<CefrLevel, Integer> byLevel = new EnumMap<>(CefrLevel.class);
		for (CefrLevel level : CefrLevel.values()) {
			byLevel.put(level, 0);
		}
		// [total, without a level]
		int[] counts = { 0, 0 };
		jdbc.query("select status, cefr_level, count(*) as n from " + table
				+ " where user_id = :userId and language_code = :language group by status, cefr_level", params, rs -> {
					int n = rs.getInt("n");
					counts[0] += n;
					byStatus.merge(KnowledgeStatus.valueOf(rs.getString("status")), n, Integer::sum);
					String level = rs.getString("cefr_level");
					if (level == null) {
						counts[1] += n;
					}
					else {
						byLevel.merge(CefrLevel.valueOf(level), n, Integer::sum);
					}
				});
		return new Material(counts[0], byStatus, byLevel, counts[1]);
	}

	/** The learner's time zone; UTC if it can't be read. */
	private ZoneId zoneOf(UUID userId) {
		List<String> found = jdbc.queryForList("select timezone from users where id = :userId",
				new MapSqlParameterSource("userId", userId), String.class);
		try {
			return ZoneId.of(found.isEmpty() ? "UTC" : found.getFirst());
		}
		catch (RuntimeException ex) {
			return ZoneOffset.UTC;
		}
	}

}
