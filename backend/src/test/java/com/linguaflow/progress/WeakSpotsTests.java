package com.linguaflow.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.ai.AiUseCase;
import com.linguaflow.ai.FakeLlmClient;
import com.linguaflow.ai.LlmClient;
import com.linguaflow.ai.job.AiJobWorker;
import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.testsupport.TestUsers;
import com.linguaflow.testsupport.TestUsers.TestUser;
import com.linguaflow.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
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

/** "Practise my weak spots" end to end (PRG-06), with a scripted fake model. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class WeakSpotsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Pattern COUNT = Pattern.compile("Write (\\d+) exercises");

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

	@Autowired
	private LlmClient client;

	@Autowired
	private AiJobWorker worker;

	private TestUser anna;

	private TestUser ben;

	private UUID set;

	private FakeLlmClient fake() {
		return (FakeLlmClient) client;
	}

	@BeforeEach
	void setUp() {
		users.deleteAll();
		jdbc.update("delete from ai_jobs");
		fake().reset();
		TestUsers testUsers = new TestUsers(registration, accessTokens, jdbc);
		anna = testUsers.learner("anna@example.de");
		ben = testUsers.learner("ben@example.de");
		jdbc.update("update user_languages set preferred_exercise_types = '{FILL_BLANK}' where user_id = ?", anna.id());
		set = UUID.randomUUID();
		jdbc.update("insert into exercise_sets (id, user_id, language_code, source, request, status) "
				+ "values (?, ?, 'de', 'MANUAL', '{\"count\":3}', 'READY')", set, anna.id());
	}

	@AfterEach
	void tearDown() {
		fake().reset();
	}

	private UUID topic(String title) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into grammar_topics (id, user_id, language_code, title, normalized_title) "
				+ "values (?, ?, 'de', ?, ?)", id, anna.id(), title, title.toLowerCase());
		return id;
	}

	private UUID gapExercise(String text, String answer, UUID topic, UUID word) {
		UUID id = UUID.randomUUID();
		Integer position = jdbc.queryForObject("select count(*) from exercises where exercise_set_id = ?",
				Integer.class, set);
		jdbc.update("insert into exercises (id, exercise_set_id, user_id, position, type, prompt, solution) "
				+ "values (?, ?, ?, ?, 'FILL_BLANK', ?::jsonb, ?::jsonb)", id, set, anna.id(), position,
				"{\"text\":\"" + text + "\"}", "{\"blanks\":[{\"answer\":\"" + answer + "\",\"alternatives\":[]}]}");
		jdbc.update("insert into exercise_grammar_topics (exercise_id, grammar_topic_id) values (?, ?)", id, topic);
		jdbc.update("insert into exercise_vocabulary (exercise_id, vocabulary_item_id) values (?, ?)", id, word);
		return id;
	}

	private void answer(UUID exercise, String blank) throws Exception {
		mvc.perform(post("/api/exercises/{id}/attempts", exercise).header("Authorization", anna.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"blanks\":[\"" + blank + "\"]}")).andExpect(status().isCreated());
	}

	@Test
	void aSetIsWrittenForTheWeakestTopicTestingItsMistakes() throws Exception {
		UUID hund = UUID.randomUUID();
		jdbc.update("insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma, translation, "
				+ "ai_status) values (?, ?, 'de', 'der Hund', 'hund', 'dog', 'READY')", hund, anna.id());
		UUID haus = UUID.randomUUID();
		jdbc.update("insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma, translation, "
				+ "ai_status) values (?, ?, 'de', 'das Haus', 'haus', 'house', 'READY')", haus, anna.id());
		UUID dativ = topic("Dativ");
		UUID perfekt = topic("Perfekt");
		UUID weak = gapExercise("Ich gebe ___ Hund Wasser.", "dem", dativ, hund);
		UUID solid = gapExercise("Ich bin ___.", "gegangen", perfekt, haus);
		// Answering through the API updates the progress and the weak-point scores
		answer(weak, "den");
		answer(weak, "den");
		for (int i = 0; i < 6; i++) {
			answer(solid, "gegangen");
		}

		fake().respondTo(AiUseCase.EXERCISE_GENERATION, prompt -> {
			Matcher matcher = COUNT.matcher(prompt.user());
			assertThat(matcher.find()).isTrue();
			List<Map<String, Object>> items = new ArrayList<>();
			for (int i = 0; i < Integer.parseInt(matcher.group(1)); i++) {
				items.add(Map.of("prompt", Map.of("text", "Ich helfe ___ Hund Nummer " + i + "."), "solution",
						Map.of("blanks", List.of(Map.of("answer", "dem", "alternatives", List.of())), "explanation",
								"Helfen takes the Dativ."),
						"words", List.of("der Hund"), "topics", List.of("Dativ")));
			}
			return JSON.writeValueAsString(Map.of("exercises", items));
		});

		JsonNode started = JSON.readTree(mvc
			.perform(post("/api/progress/weak-spots/exercises").header("Authorization", anna.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"count\":3}"))
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString());
		// Only the weak topic: the solid Perfekt scores too low
		assertThat(started.get("grammarTopics")).hasSize(1);
		assertThat(started.get("grammarTopics").get(0).get("id").asString()).isEqualTo(dativ.toString());
		assertThat(started.get("words").asInt()).isEqualTo(1);
		String mistake = "WRONG_GAP in Dativ (2 times), e.g. answered \"Ich gebe den Hund Wasser.\" where "
				+ "\"Ich gebe dem Hund Wasser.\" is right";
		assertThat(started.get("focusMistakes").get(0).asString()).isEqualTo(mistake);
		String setId = started.get("exerciseSetId").asString();
		assertThat(jdbc.queryForObject("select source from exercise_sets where id = ?::uuid", String.class, setId))
			.isEqualTo("WEAK_SPOTS");

		worker.runDueJobs();

		String prompt = fake().calls().getFirst().prompt().user();
		assertThat(prompt).contains("The learner keeps making these mistakes")
			.contains("<focus_mistakes>")
			.contains("- " + mistake)
			.contains("- Dativ")
			.doesNotContain("- Perfekt")
			.contains("- der Hund (dog)");
		assertThat(jdbc.queryForObject("select status from exercise_sets where id = ?::uuid", String.class, setId))
			.isEqualTo("READY");
		assertThat(jdbc.queryForObject("select count(*) from exercises where exercise_set_id = ?::uuid",
				Integer.class, setId))
			.isEqualTo(3);
	}

	@Test
	void withoutWeakSpotsThereIsNothingToPractise() throws Exception {
		mvc.perform(post("/api/progress/weak-spots/exercises").header("Authorization", ben.bearer()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NOTHING_TO_PRACTISE"));
		mvc.perform(post("/api/progress/weak-spots/exercises").header("Authorization", ben.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"count\":21}")).andExpect(status().isBadRequest());
	}

}
