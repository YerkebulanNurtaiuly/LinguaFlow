package com.linguaflow.progress;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.linguaflow.exercise.MistakeExamples;
import com.linguaflow.user.ActiveLanguageService;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Frequent mistakes (PRG-04): the learner's mistakes in the active language, grouped by
 * error type or by grammar topic, most frequent first, each group with the other
 * dimension broken down and the latest examples of their own wrong answers.
 */
@Service
public class FrequentMistakeService {

	public static final int MAX_GROUPS = 50;

	public static final int MAX_EXAMPLES = 10;

	public static final int MAX_DAYS = 3650;

	/** What mistakes are grouped by. */
	public enum Grouping {

		TYPE, TOPIC

	}

	/** What to list. */
	public record Query(Grouping groupBy, @Nullable Integer days, @Nullable UUID grammarTopicId, int limit,
			int examples) {
	}

	/** A topic, or null for mistakes without one (e.g. a wrong word). */
	public record Topic(UUID id, String title) {
	}

	/** One slice of a group: a topic (in a TYPE group) or an error type (in a TOPIC group). */
	public record Slice(@Nullable String errorType, @Nullable Topic topic, int count) {
	}

	/** One of the learner's wrong answers. */
	public record Example(UUID mistakeId, String errorType, @Nullable String excerpt, @Nullable String explanation,
			Instant madeAt, @Nullable Topic topic, MistakeExamples.@Nullable Example attempt) {
	}

	/**
	 * One group.
	 * @param errorType TYPE grouping: the error type
	 * @param topic TOPIC grouping: the topic, null for the mistakes without one
	 */
	public record Group(@Nullable String errorType, @Nullable Topic topic, int count, Instant lastSeenAt,
			List<Slice> breakdown, List<Example> examples) {
	}

	/** @param total mistakes counted, in every group */
	public record Result(Grouping groupBy, @Nullable Instant since, int total, List<Group> groups) {
	}

	private final NamedParameterJdbcTemplate jdbc;

	private final ActiveLanguageService activeLanguages;

	private final MistakeExamples describer;

	private final Clock clock;

	FrequentMistakeService(NamedParameterJdbcTemplate jdbc, ActiveLanguageService activeLanguages,
			MistakeExamples describer, Clock clock) {
		this.jdbc = jdbc;
		this.activeLanguages = activeLanguages;
		this.describer = describer;
		this.clock = clock;
	}

	/** Counts per (error type, topic), the material for either grouping. */
	private record Cell(String errorType, @Nullable Topic topic, int count, Instant lastSeenAt) {
	}

	@Transactional(readOnly = true)
	public Result frequent(UUID userId, Query query) {
		String language = activeLanguages.requireActive(userId);
		Instant since = query.days() == null ? null : clock.instant().minus(Duration.ofDays(query.days()));
		MapSqlParameterSource params = new MapSqlParameterSource("userId", userId).addValue("language", language)
			.addValue("since", since == null ? null : Timestamp.from(since))
			.addValue("topic", query.grammarTopicId());
		String from = """
				  from mistakes m
				  join exercise_attempts a on a.id = m.attempt_id
				  join exercises e on e.id = a.exercise_id
				  join exercise_sets s on s.id = e.exercise_set_id
				  left join grammar_topics t on t.id = m.grammar_topic_id
				 where m.user_id = :userId and s.language_code = :language""" + (since == null ? ""
				: " and m.created_at >= :since") + (query.grammarTopicId() == null ? ""
						: " and m.grammar_topic_id = :topic") + "\n";

		List<Cell> cells = jdbc.query("""
				select m.error_type, m.grammar_topic_id, t.title, count(*) as mistakes, max(m.created_at) as last_seen
				""" + from + """
				 group by m.error_type, m.grammar_topic_id, t.title
				""", params, (rs, n) -> new Cell(rs.getString("error_type"),
				topic(rs.getObject("grammar_topic_id", UUID.class), rs.getString("title")), rs.getInt("mistakes"),
				rs.getTimestamp("last_seen").toInstant()));

		// Group the cells by the chosen dimension
		Map<Object, List<Cell>> byKey = new LinkedHashMap<>();
		for (Cell cell : cells) {
			byKey.computeIfAbsent(key(query.groupBy(), cell), k -> new ArrayList<>()).add(cell);
		}
		List<Group> groups = new ArrayList<>();
		for (List<Cell> members : byKey.values()) {
			Cell first = members.getFirst();
			int count = members.stream().mapToInt(Cell::count).sum();
			Instant last = members.stream().map(Cell::lastSeenAt).max(Comparator.naturalOrder()).orElseThrow();
			List<Slice> breakdown = members.stream()
				.sorted(Comparator.comparingInt(Cell::count).reversed().thenComparing(Cell::errorType))
				.map(cell -> query.groupBy() == Grouping.TYPE ? new Slice(null, cell.topic(), cell.count())
						: new Slice(cell.errorType(), null, cell.count()))
				.toList();
			groups.add(new Group(query.groupBy() == Grouping.TYPE ? first.errorType() : null,
					query.groupBy() == Grouping.TOPIC ? first.topic() : null, count, last, breakdown, List.of()));
		}
		groups.sort(Comparator.comparingInt(Group::count).reversed().thenComparing(Group::lastSeenAt,
				Comparator.reverseOrder()));
		int total = groups.stream().mapToInt(Group::count).sum();
		List<Group> shown = groups.stream().limit(query.limit()).toList();
		if (query.examples() > 0 && !shown.isEmpty()) {
			shown = withExamples(userId, query, from, params, shown);
		}
		return new Result(query.groupBy(), since, total, shown);
	}

	/** A mistake picked as an example, before its attempt is described. */
	private record ExampleRow(UUID mistakeId, UUID attemptId, String errorType, @Nullable String excerpt,
			@Nullable String explanation, Instant madeAt, @Nullable Topic topic) {
	}

	private List<Group> withExamples(UUID userId, Query query, String from, MapSqlParameterSource params,
			List<Group> groups) {
		String partition = query.groupBy() == Grouping.TYPE ? "m.error_type" : "m.grammar_topic_id";
		String sql = "select * from (select m.id, m.attempt_id, m.error_type, m.excerpt, m.explanation, "
				+ "m.created_at, m.grammar_topic_id, t.title, row_number() over (partition by " + partition
				+ " order by m.created_at desc, m.id desc) as rn\n" + from
				+ ") ranked where rn <= :examples order by created_at desc, id desc";
		List<ExampleRow> rows = jdbc.query(sql, params.addValue("examples", query.examples()),
				(rs, n) -> new ExampleRow(rs.getObject("id", UUID.class), rs.getObject("attempt_id", UUID.class),
						rs.getString("error_type"), rs.getString("excerpt"), rs.getString("explanation"),
						rs.getTimestamp("created_at").toInstant(),
						topic(rs.getObject("grammar_topic_id", UUID.class), rs.getString("title"))));
		var described = describer.describe(userId, rows.stream().map(ExampleRow::attemptId).distinct().toList());
		List<Group> result = new ArrayList<>();
		for (Group group : groups) {
			Object groupKey = key(query.groupBy(), group.errorType(), group.topic());
			List<Example> examples = rows.stream()
				.filter(row -> groupKey.equals(key(query.groupBy(), row.errorType(), row.topic())))
				.map(row -> new Example(row.mistakeId(), row.errorType(), row.excerpt(), row.explanation(),
						row.madeAt(), row.topic(), described.get(row.attemptId())))
				.toList();
			result.add(new Group(group.errorType(), group.topic(), group.count(), group.lastSeenAt(),
					group.breakdown(), examples));
		}
		return result;
	}

	private static Object key(Grouping grouping, Cell cell) {
		return key(grouping, cell.errorType(), cell.topic());
	}

	/** The group a mistake belongs to; mistakes without a topic share one TOPIC group. */
	private static Object key(Grouping grouping, @Nullable String errorType, @Nullable Topic topic) {
		if (grouping == Grouping.TYPE) {
			return Objects.requireNonNull(errorType);
		}
		return topic == null ? "" : topic.id();
	}

	private static @Nullable Topic topic(@Nullable UUID id, @Nullable String title) {
		return id == null ? null : new Topic(id, title == null ? "" : title);
	}

}
