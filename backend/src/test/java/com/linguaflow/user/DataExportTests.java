package com.linguaflow.user;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.testsupport.IntegrationTest;
import com.linguaflow.testsupport.TestData;
import com.linguaflow.testsupport.TestData.ExerciseSetIds;
import com.linguaflow.testsupport.TestUsers;
import com.linguaflow.testsupport.TestUsers.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-02: the data export is complete and the learner's own, and deleting the account
 * leaves nothing behind.
 */
@IntegrationTest
class DataExportTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NamedParameterJdbcTemplate named;

	@Autowired
	private UserRepository users;

	@Autowired
	private RegistrationService registration;

	@Autowired
	private AccessTokenService accessTokens;

	private TestUser anna;

	private TestUser ben;

	private UUID exercise;

	@BeforeEach
	void setUp() throws Exception {
		users.deleteAll();
		TestUsers testUsers = new TestUsers(registration, accessTokens, jdbc);
		anna = testUsers.learner("anna@example.de");
		ben = testUsers.learner("ben@example.de");
		TestData data = new TestData(jdbc);
		UUID lesson = data.lesson(anna.id(), "Lektion 1").insert();
		UUID hund = data.word(anna.id(), "der Hund").translation("dog").noun("MASCULINE").inLesson(lesson).insert();
		jdbc.update("insert into vocabulary_examples (id, vocabulary_item_id, sentence, translation, position, "
				+ "ai_generated, created_at, updated_at) values (?, ?, 'Der Hund bellt.', 'The dog barks.', 0, false, now(), now())",
				UUID.randomUUID(), hund);
		UUID dativ = data.topic(anna.id(), "Dativ").inLesson(lesson).insert();
		ExerciseSetIds set = data.exerciseSet(anna.id())
			.with(TestData.fillBlank("Ich gebe ___ Hund Wasser.", "dem").topics(dativ).words(hund))
			.insert();
		exercise = set.exercise(0);
		// A wrong answer through the API: an attempt, a mistake and progress rows
		mvc.perform(post("/api/exercises/{id}/attempts", exercise).header("Authorization", anna.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"blanks\": [\"den\"]}")).andExpect(status().isCreated());
		data.word(ben.id(), "die Katze").translation("cat").insert();
	}

	@Test
	void everyTableIsExportedOrDeliberatelyLeftOut() {
		Set<String> tables = new HashSet<>(jdbc.queryForList("""
				select table_name from information_schema.tables
				where table_schema = current_schema() and table_type = 'BASE TABLE'""", String.class));

		assertThat(DataExportService.knownTables())
			.as("Add new tables to DataExportService.TABLES (exported) or NOT_EXPORTED (with a reason)")
			.containsExactlyInAnyOrderElementsOf(tables);
	}

	@Test
	void theExportHoldsTheLearnersDataAndNoOneElses() throws Exception {
		MvcResult result = mvc.perform(get("/api/users/me/export").header("Authorization", anna.bearer()))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Disposition",
					org.hamcrest.Matchers.matchesPattern("attachment; filename=\"linguaflow-export-\\d{4}-\\d{2}-\\d{2}\\.json\"")))
			.andReturn();
		String body = result.getResponse().getContentAsString();
		JsonNode export = JSON.readTree(body);

		assertThat(export.get("format").asString()).isEqualTo("linguaflow-export");
		assertThat(export.get("version").asInt()).isEqualTo(DataExportService.FORMAT_VERSION);
		assertThat(export.get("account").get("email").asString()).isEqualTo("anna@example.de");
		JsonNode tables = export.get("tables");
		assertThat(tables.propertyNames()).containsExactlyElementsOf(DataExportService.TABLES.keySet());
		assertThat(tables.get("vocabulary_items")).hasSize(1);
		assertThat(tables.get("vocabulary_items").get(0).get("lemma").asString()).isEqualTo("der Hund");
		assertThat(tables.get("vocabulary_examples")).hasSize(1);
		assertThat(tables.get("session_vocabulary")).hasSize(1);
		assertThat(tables.get("exercise_attempts")).hasSize(1);
		assertThat(tables.get("mistakes")).isNotEmpty();
		assertThat(tables.get("grammar_topic_progress")).hasSize(1);
		// jsonb columns are embedded as JSON, not as strings
		assertThat(tables.get("exercises").get(0).get("prompt").get("text").asString())
			.isEqualTo("Ich gebe ___ Hund Wasser.");
		assertThat(tables.get("exercise_attempts").get(0).get("answer").get("blanks").get(0).asString())
			.isEqualTo("den");
		// Secrets and other users never appear
		assertThat(body).doesNotContain("password_hash", "{bcrypt}", "token_hash", "die Katze", ben.id().toString());
		assertThat(export.get("notExported").toString()).contains("refresh_tokens", "user_action_tokens");
	}

	@Test
	void theExportNeedsALogin() throws Exception {
		mvc.perform(get("/api/users/me/export")).andExpect(status().isUnauthorized());
	}

	@Test
	void deletingTheAccountLeavesNothingBehind() throws Exception {
		assertThat(rowsOf(anna.id())).as("rows before deleting").isPositive();

		mvc.perform(delete("/api/users/me").header("Authorization", anna.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\": \"correct-horse-battery\"}")).andExpect(status().isNoContent());

		assertThat(rowsOf(anna.id())).as("rows left after deleting").isZero();
		for (String table : List.of("users", "refresh_tokens", "user_action_tokens")) {
			String column = table.equals("users") ? "id" : "user_id";
			assertThat(jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?",
					Integer.class, anna.id()))
				.as(table)
				.isZero();
		}
		// Ben is untouched
		assertThat(rowsOf(ben.id())).isPositive();
	}

	/** Every row the export would show for the user, over all tables. */
	private int rowsOf(UUID userId) {
		int rows = 0;
		for (Map.Entry<String, String> table : DataExportService.TABLES.entrySet()) {
			rows += named.queryForList(table.getValue(), Map.of("userId", userId)).size();
		}
		return rows;
	}

}
