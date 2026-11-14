package com.linguaflow.user;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.linguaflow.common.error.NotFoundException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The learner's own copy of everything stored about them (SEC-02, GDPR art. 15 and 20):
 * their account and every row of learning data, AI job and AI usage, as JSON.
 * <p>
 * Each table is read with an explicit query scoped to the user, children through their
 * parent ({@link #TABLES}). Some tables are left out on purpose ({@link #NOT_EXPORTED}):
 * secrets (password hash, token hashes) and data that isn't the user's (catalogs, the
 * shared AI cache). {@code DataExportTests} fails when a table is in neither list, so a new
 * table can't silently go missing from exports.
 */
@Service
public class DataExportService {

	/** Bumped when the shape of the export changes. */
	public static final int FORMAT_VERSION = 1;

	/** Table → the query selecting this user's rows (parameter {@code :userId}), in a sensible reading order. */
	static final Map<String, String> TABLES = tables();

	/** Tables that are deliberately not exported, with the reason. */
	static final Map<String, String> NOT_EXPORTED = Map.of(
			"users", "exported as \"account\", without the password hash",
			"refresh_tokens", "hashes of login session secrets",
			"user_action_tokens", "hashes of email-verification and password-reset secrets",
			"ai_cache", "shared language data, not linked to any user",
			"languages", "the list of supported languages",
			"grammar_topic_catalog", "the shared catalog of grammar topics",
			"flyway_schema_history", "database migration history");

	/** Columns that are internal bookkeeping rather than the user's data. */
	private static final Set<String> HIDDEN_COLUMNS = Set.of("locked_by", "locked_at");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final NamedParameterJdbcTemplate jdbc;

	private final Clock clock;

	DataExportService(NamedParameterJdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** The export; 404 when the user no longer exists. */
	@Transactional(readOnly = true)
	public Map<String, Object> export(UUID userId) {
		Map<String, Object> params = Map.of("userId", userId);
		List<Map<String, Object>> account = rows("""
				select id, email, display_name, role, enabled, email_verified_at, interface_language_code, timezone,
				       last_login_at, created_at, updated_at
				from users where id = :userId""", params);
		if (account.isEmpty()) {
			throw new NotFoundException("User not found");
		}
		Map<String, Object> tables = new LinkedHashMap<>();
		TABLES.forEach((table, sql) -> tables.put(table, rows(sql, params)));
		Map<String, Object> export = new LinkedHashMap<>();
		export.put("format", "linguaflow-export");
		export.put("version", FORMAT_VERSION);
		export.put("exportedAt", Instant.now(clock));
		export.put("account", account.getFirst());
		export.put("tables", tables);
		export.put("notExported", NOT_EXPORTED.keySet().stream().sorted().toList());
		return export;
	}

	private List<Map<String, Object>> rows(String sql, Map<String, Object> params) {
		return jdbc.query(sql, params, (rs, n) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
				String column = rs.getMetaData().getColumnLabel(i);
				if (HIDDEN_COLUMNS.contains(column)) {
					continue;
				}
				// jsonb comes back as a driver object; read it as text and embed it as JSON
				String json = rs.getMetaData().getColumnTypeName(i).startsWith("json") ? rs.getString(i) : null;
				row.put(column, json != null ? JSON.readTree(json) : value(rs.getObject(i)));
			}
			return row;
		});
	}

	/** JDBC values as JSON-friendly ones: arrays as lists, timestamps as instants, dates as dates. */
	private static Object value(Object value) throws SQLException {
		return switch (value) {
			case null -> null;
			case Timestamp timestamp -> timestamp.toInstant();
			case java.sql.Date date -> date.toLocalDate();
			case Array array -> {
				List<Object> items = new ArrayList<>();
				for (Object item : (Object[]) array.getArray()) {
					items.add(value(item));
				}
				yield items;
			}
			default -> value;
		};
	}

	private static Map<String, String> tables() {
		Map<String, String> tables = new LinkedHashMap<>();
		ownedBy(tables, "user_languages", "created_at, language_code");
		owned(tables, "tags");
		owned(tables, "vocabulary_items");
		tables.put("vocabulary_examples", """
				select e.* from vocabulary_examples e join vocabulary_items v on v.id = e.vocabulary_item_id
				where v.user_id = :userId order by e.vocabulary_item_id, e.position""");
		tables.put("vocabulary_item_tags", """
				select t.* from vocabulary_item_tags t join vocabulary_items v on v.id = t.vocabulary_item_id
				where v.user_id = :userId order by t.vocabulary_item_id, t.tag_id""");
		owned(tables, "grammar_topics");
		tables.put("grammar_topic_status_history", """
				select h.* from grammar_topic_status_history h join grammar_topics g on g.id = h.grammar_topic_id
				where g.user_id = :userId order by h.changed_at, h.id""");
		owned(tables, "study_sessions");
		tables.put("session_vocabulary", """
				select s.* from session_vocabulary s join study_sessions l on l.id = s.study_session_id
				where l.user_id = :userId order by s.study_session_id, s.added_at""");
		tables.put("session_grammar", """
				select s.* from session_grammar s join study_sessions l on l.id = s.study_session_id
				where l.user_id = :userId order by s.study_session_id, s.added_at""");
		owned(tables, "flashcards");
		ownedBy(tables, "srs_state", "flashcard_id");
		ownedBy(tables, "flashcard_reviews", "reviewed_at, id");
		owned(tables, "exercise_sets");
		ownedBy(tables, "exercises", "exercise_set_id, position");
		tables.put("exercise_grammar_topics", """
				select x.* from exercise_grammar_topics x join exercises e on e.id = x.exercise_id
				where e.user_id = :userId order by x.exercise_id, x.grammar_topic_id""");
		tables.put("exercise_vocabulary", """
				select x.* from exercise_vocabulary x join exercises e on e.id = x.exercise_id
				where e.user_id = :userId order by x.exercise_id, x.vocabulary_item_id""");
		ownedBy(tables, "exercise_attempts", "attempted_at, id");
		owned(tables, "mistakes");
		owned(tables, "practice_sessions");
		tables.put("practice_session_items", """
				select i.* from practice_session_items i join practice_sessions p on p.id = i.practice_session_id
				where p.user_id = :userId order by i.practice_session_id, i.position""");
		ownedBy(tables, "grammar_topic_progress", "grammar_topic_id");
		ownedBy(tables, "vocabulary_progress", "vocabulary_item_id");
		ownedBy(tables, "mistake_counts", "language_code, error_type");
		owned(tables, "ai_jobs");
		owned(tables, "ai_requests");
		return tables;
	}

	private static void owned(Map<String, String> tables, String table) {
		ownedBy(tables, table, "created_at, id");
	}

	private static void ownedBy(Map<String, String> tables, String table, String order) {
		tables.put(table, "select * from " + table + " where user_id = :userId order by " + order);
	}

	/** Every table the export covers or deliberately leaves out. */
	static Set<String> knownTables() {
		Set<String> known = new HashSet<>(TABLES.keySet());
		known.addAll(NOT_EXPORTED.keySet());
		return known;
	}

}
