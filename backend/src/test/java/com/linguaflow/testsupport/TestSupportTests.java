package com.linguaflow.testsupport;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.linguaflow.ai.AiUseCase;
import com.linguaflow.ai.FakeLlmClient;
import com.linguaflow.ai.LlmClient;
import com.linguaflow.ai.LlmOptions;
import com.linguaflow.ai.LlmPrompt;
import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.testsupport.TestData.ExerciseSetIds;
import com.linguaflow.testsupport.TestUsers.TestUser;
import com.linguaflow.user.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The QA-01 foundation: a database per context, the data builders and the fake LLM wiring. */
@IntegrationTest
class TestSupportTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private UserRepository users;

	@Autowired
	private RegistrationService registration;

	@Autowired
	private AccessTokenService accessTokens;

	private TestUser anna;

	private TestData data;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		anna = new TestUsers(registration, accessTokens, jdbc).learner("anna@example.de");
		data = new TestData(jdbc);
	}

	@Test
	void eachContextHasADatabaseOfItsOwnInTheSharedContainer() {
		assertThat(((HikariDataSource) dataSource).getJdbcUrl()).matches("jdbc:postgresql://.+:\\d+/test_\\d+");
		assertThat(jdbc.queryForObject("select current_database()", String.class)).startsWith("test_");
	}

	@Test
	void builtMaterialIsServedByTheApi() throws Exception {
		UUID lesson = data.lesson(anna.id(), "Lektion 1").active().insert();
		UUID hund = data.word(anna.id(), "der Hund").translation("dog").noun("MASCULINE").inLesson(lesson).insert();
		UUID dativ = data.topic(anna.id(), "Der Dativ").cefrLevel("A2").inLesson(lesson).insert();

		mvc.perform(get("/api/vocabulary").param("q", "hund").header("Authorization", anna.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].id").value(hund.toString()))
			.andExpect(jsonPath("$.content[0].article").value("der"))
			.andExpect(jsonPath("$.content[0].aiStatus").value("READY"));
		assertThat(jdbc.queryForObject("select normalized_title from grammar_topics where id = ?", String.class,
				dativ))
			.isEqualTo("dativ");
		mvc.perform(get("/api/study-sessions/{id}/material", lesson).header("Authorization", anna.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.vocabularyItemIds[0]").value(hund.toString()))
			.andExpect(jsonPath("$.grammarTopicIds[0]").value(dativ.toString()));

		ExerciseSetIds set = data.exerciseSet(anna.id())
			.with(TestData.fillBlank("Ich helfe ___ Hund.", "dem").topics(dativ).words(hund),
					TestData.multipleChoice("Ich gebe ___ Frau das Buch.", List.of("der", "die"), 0),
					TestData.translation("I help the dog.", "Ich helfe dem Hund."))
			.insert();
		mvc.perform(get("/api/exercise-sets/{id}", set.id()).header("Authorization", anna.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.exercises.length()").value(3))
			.andExpect(jsonPath("$.exercises[0].id").value(set.exercise(0).toString()))
			.andExpect(jsonPath("$.exercises[2].type").value("TRANSLATION"));
	}

	@Test
	void theFakeIsInjectedAndStartsEmpty(FakeLlmClient fake, @Autowired LlmClient client) {
		assertThat(fake).isSameAs(client);
		assertThat(fake.calls()).isEmpty();
		// Leaves a call behind; the extension clears it before the next test
		fake.complete(new LlmPrompt("system", "user"), Map.of("type", "object"), new LlmOptions(
				AiUseCase.VOCABULARY_ENRICHMENT, "fake-model", LlmOptions.Effort.LOW, 100, Duration.ofSeconds(5)));
		assertThat(fake.calls()).hasSize(1);
	}

	@Test
	void theFakeIsClearedBetweenTests(FakeLlmClient fake) {
		assertThat(fake.calls()).isEmpty();
	}

}
