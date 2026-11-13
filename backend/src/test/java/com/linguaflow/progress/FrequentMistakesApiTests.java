package com.linguaflow.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.testsupport.TestUsers;
import com.linguaflow.testsupport.TestUsers.TestUser;
import com.linguaflow.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Frequent mistakes through the API, from real wrong answers (PRG-04). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class FrequentMistakesApiTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private UserRepository users;

	@Autowired
	private RegistrationService registration;

	@Autowired
	private AccessTokenService accessTokens;

	private TestUser anna;

	private TestUser ben;

	private UUID set;

	private UUID dativ;

	private UUID perfekt;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		TestUsers testUsers = new TestUsers(registration, accessTokens, jdbc);
		anna = testUsers.learner("anna@example.de");
		ben = testUsers.learner("ben@example.de");
		set = UUID.randomUUID();
		jdbc.update("insert into exercise_sets (id, user_id, language_code, source, request, status) "
				+ "values (?, ?, 'de', 'MANUAL', '{\"count\":3}', 'READY')", set, anna.id());
		dativ = topic("Dativ");
		perfekt = topic("Perfekt");
	}

	private UUID topic(String title) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into grammar_topics (id, user_id, language_code, title, normalized_title) "
				+ "values (?, ?, 'de', ?, ?)", id, anna.id(), title, title.toLowerCase());
		return id;
	}

	private UUID exercise(String type, String prompt, String solution, UUID topic) {
		UUID id = UUID.randomUUID();
		Integer position = jdbc.queryForObject("select count(*) from exercises where exercise_set_id = ?",
				Integer.class, set);
		jdbc.update("insert into exercises (id, exercise_set_id, user_id, position, type, prompt, solution) "
				+ "values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)", id, set, anna.id(), position, type, prompt, solution);
		jdbc.update("insert into exercise_grammar_topics (exercise_id, grammar_topic_id) values (?, ?)", id, topic);
		return id;
	}

	private void answer(UUID exercise, String body) throws Exception {
		mvc.perform(post("/api/exercises/{id}/attempts", exercise).header("Authorization", anna.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).andExpect(status().isCreated());
	}

	private JsonNode mistakes(TestUser user, String query) throws Exception {
		return JSON.readTree(mvc.perform(get("/api/progress/mistakes" + query).header("Authorization", user.bearer()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString());
	}

	/** Three wrong gaps (two on Dativ, one on Perfekt), one wrong choice, one right answer. */
	private void makeMistakes() throws Exception {
		UUID gap = exercise("FILL_BLANK", "{\"text\":\"Ich gebe ___ Hund Wasser.\"}",
				"{\"blanks\":[{\"answer\":\"dem\",\"alternatives\":[]}]}", dativ);
		UUID choice = exercise("MULTIPLE_CHOICE", "{\"question\":\"Ich helfe ___ Frau.\",\"options\":[\"der\",\"die\"]}",
				"{\"correctIndex\":0}", dativ);
		UUID past = exercise("FILL_BLANK", "{\"text\":\"Ich bin nach Hause ___.\",\"hint\":\"(gehen)\"}",
				"{\"blanks\":[{\"answer\":\"gegangen\",\"alternatives\":[]}]}", perfekt);
		answer(gap, "{\"blanks\":[\"den\"]}");
		answer(gap, "{\"blanks\":[\"der\"]}");
		answer(choice, "{\"index\":1}");
		answer(past, "{\"blanks\":[\"gegangen\"]}");
		answer(past, "{\"blanks\":[\"gegeht\"]}");
	}

	@Test
	void mistakesAreGroupedByTypeWithTheLearnersOwnAnswers() throws Exception {
		makeMistakes();

		JsonNode body = mistakes(anna, "");
		assertThat(body.get("groupBy").asString()).isEqualTo("TYPE");
		assertThat(body.get("total").asInt()).isEqualTo(4);
		assertThat(body.get("since").isNull()).isTrue();
		JsonNode groups = body.get("groups");
		assertThat(groups).hasSize(2);
		JsonNode gaps = groups.get(0);
		assertThat(gaps.get("errorType").asString()).isEqualTo("WRONG_GAP");
		assertThat(gaps.get("count").asInt()).isEqualTo(3);
		assertThat(gaps.get("breakdown").get(0).get("grammarTopic").get("title").asString()).isEqualTo("Dativ");
		assertThat(gaps.get("breakdown").get(0).get("count").asInt()).isEqualTo(2);
		assertThat(gaps.get("breakdown").get(1).get("grammarTopic").get("title").asString()).isEqualTo("Perfekt");

		// The latest first: the Perfekt gap
		JsonNode example = gaps.get("examples").get(0);
		assertThat(gaps.get("examples")).hasSize(3);
		assertThat(example.get("excerpt").asString()).isEqualTo("gegeht");
		assertThat(example.get("grammarTopic").get("title").asString()).isEqualTo("Perfekt");
		JsonNode attempt = example.get("attempt");
		assertThat(attempt.get("exerciseType").asString()).isEqualTo("FILL_BLANK");
		assertThat(attempt.get("exercise").asString()).isEqualTo("Ich bin nach Hause ___. (gehen)");
		assertThat(attempt.get("answer").asString()).isEqualTo("Ich bin nach Hause gegeht.");
		assertThat(attempt.get("correctAnswer").asString()).isEqualTo("Ich bin nach Hause gegangen.");

		JsonNode choices = groups.get(1);
		assertThat(choices.get("errorType").asString()).isEqualTo("WRONG_CHOICE");
		assertThat(choices.get("examples").get(0).get("attempt").get("answer").asString()).isEqualTo("die");
		assertThat(choices.get("examples").get(0).get("attempt").get("correctAnswer").asString()).isEqualTo("der");
	}

	@Test
	void mistakesCanBeGroupedByTopicAndFiltered() throws Exception {
		makeMistakes();

		JsonNode groups = mistakes(anna, "?groupBy=TOPIC").get("groups");
		assertThat(groups).hasSize(2);
		assertThat(groups.get(0).get("grammarTopic").get("id").asString()).isEqualTo(dativ.toString());
		assertThat(groups.get(0).get("count").asInt()).isEqualTo(3);
		assertThat(groups.get(0).get("breakdown").get(0).get("errorType").asString()).isEqualTo("WRONG_GAP");
		assertThat(groups.get(0).get("breakdown").get(0).get("count").asInt()).isEqualTo(2);
		assertThat(groups.get(0).get("breakdown").get(1).get("errorType").asString()).isEqualTo("WRONG_CHOICE");
		assertThat(groups.get(1).get("grammarTopic").get("title").asString()).isEqualTo("Perfekt");

		JsonNode onePerfekt = mistakes(anna, "?grammarTopicId=" + perfekt);
		assertThat(onePerfekt.get("total").asInt()).isEqualTo(1);

		JsonNode limited = mistakes(anna, "?limit=1&examples=0&days=7");
		assertThat(limited.get("groups")).hasSize(1);
		assertThat(limited.get("total").asInt()).isEqualTo(4);
		assertThat(limited.get("groups").get(0).get("examples")).isEmpty();
		assertThat(limited.get("since").isNull()).isFalse();

		// Ben has made no mistakes, and Anna's aren't his
		JsonNode bens = mistakes(ben, "");
		assertThat(bens.get("total").asInt()).isZero();
		assertThat(bens.get("groups")).isEmpty();
	}

	@Test
	void parametersAreChecked() throws Exception {
		for (String query : new String[] { "?days=0", "?limit=51", "?examples=11" }) {
			mvc.perform(get("/api/progress/mistakes" + query).header("Authorization", anna.bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
		mvc.perform(get("/api/progress/mistakes?groupBy=COLOUR").header("Authorization", anna.bearer()))
			.andExpect(status().isBadRequest());
	}

}
