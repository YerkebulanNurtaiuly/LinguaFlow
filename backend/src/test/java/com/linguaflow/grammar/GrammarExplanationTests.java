package com.linguaflow.grammar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.ai.AiUseCase;
import com.linguaflow.ai.FakeLlmClient;
import com.linguaflow.ai.LlmClient;
import com.linguaflow.ai.job.AiJobWorker;
import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.testsupport.OwnershipAssertions;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AI explanations of grammar topics end to end (GRM-04), against a scripted fake model. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class GrammarExplanationTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Map<String, Object> DATIV = Map.of("summary",
			"The dative marks the indirect object: the person who receives something.", "rules",
			List.of("After mit, nach, bei, seit, von, zu and aus, always use the dative.",
					"Articles change:\nder → dem\ndie → der\ndas → dem\ndie (plural) → den + -n"),
			"examples",
			List.of(Map.of("sentence", "Ich gebe dem Hund einen Knochen.", "translation", "I give the dog a bone."),
					Map.of("sentence", "Sie fährt mit dem Bus.", "translation", "She goes by bus.")),
			"commonMistakes", List.of("Using the accusative after mit: *mit den Bus → mit dem Bus."));

	@Autowired
	private MockMvc mvc;

	@Autowired
	private LlmClient client;

	@Autowired
	private AiJobWorker worker;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private UserRepository users;

	@Autowired
	private RegistrationService registration;

	@Autowired
	private AccessTokenService accessTokens;

	private final AtomicReference<String> answer = new AtomicReference<>(JSON.writeValueAsString(DATIV));

	private TestUser anna;

	private TestUser ben;

	private FakeLlmClient fake() {
		return (FakeLlmClient) client;
	}

	@BeforeEach
	void setUp() {
		users.deleteAll();
		jdbc.update("delete from ai_jobs");
		fake().reset();
		fake().respondTo(AiUseCase.GRAMMAR_EXPLANATION, prompt -> answer.get());
		TestUsers testUsers = new TestUsers(registration, accessTokens, jdbc);
		anna = testUsers.learner("anna@example.de");
		ben = testUsers.learner("ben@example.de");
	}

	@AfterEach
	void tearDown() {
		fake().reset();
	}

	private UUID topic(TestUser user, String json) throws Exception {
		String body = mvc
			.perform(post("/api/grammar-topics").header("Authorization", user.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JSON.readTree(body).get("id").asString());
	}

	private JsonNode requestExplanation(TestUser user, UUID topicId) throws Exception {
		return JSON.readTree(mvc
			.perform(post("/api/grammar-topics/{id}/explanation", topicId).header("Authorization", user.bearer()))
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString());
	}

	private JsonNode topicJson(TestUser user, UUID topicId) throws Exception {
		return JSON.readTree(mvc.perform(get("/api/grammar-topics/{id}", topicId).header("Authorization", user.bearer()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString());
	}

	@Test
	void writesAnExplanationApartFromTheLearnersNotes() throws Exception {
		UUID id = topic(anna, """
				{"title":"Dativ","description":"Indirect objects and prepositions","cefrLevel":"A2",
				 "personalNotes":"Meine Notizen"}""");

		JsonNode first = requestExplanation(anna, id);
		assertThat(first.get("alreadyRunning").asBoolean()).isFalse();
		// Asking again while it's queued returns the same job instead of paying twice
		JsonNode second = requestExplanation(anna, id);
		assertThat(second.get("aiJobId").asString()).isEqualTo(first.get("aiJobId").asString());
		assertThat(second.get("alreadyRunning").asBoolean()).isTrue();

		worker.runDueJobs();

		mvc.perform(get("/api/grammar-topics/{id}", id).header("Authorization", anna.bearer()))
			.andExpect(jsonPath("$.aiExplanation.summary").value(DATIV.get("summary")))
			.andExpect(jsonPath("$.aiExplanation.rules.length()").value(2))
			.andExpect(jsonPath("$.aiExplanation.rules[1]").value(((List<?>) DATIV.get("rules")).get(1)))
			.andExpect(jsonPath("$.aiExplanation.examples[0].sentence").value("Ich gebe dem Hund einen Knochen."))
			.andExpect(jsonPath("$.aiExplanation.examples[0].translation").value("I give the dog a bone."))
			.andExpect(jsonPath("$.aiExplanation.commonMistakes[0]").exists())
			.andExpect(jsonPath("$.aiExplanationGeneratedAt").exists())
			.andExpect(jsonPath("$.personalNotes").value("Meine Notizen"));
		mvc.perform(get("/api/ai/jobs/{id}", first.get("aiJobId").asString()).header("Authorization", anna.bearer()))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"));
		// The call was logged against Anna, under the template's version
		assertThat(jdbc.queryForObject(
				"select prompt_version from ai_requests where user_id = ? and use_case = 'GRAMMAR_EXPLANATION'",
				String.class, anna.id())).isEqualTo("grammar-explanation/v1");
	}

	@Test
	void thePromptUsesTheLearnersOwnWordsAndLearnedTopics() throws Exception {
		jdbc.update("""
				insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma, status, ai_status,
				    ai_generated_fields, extra, version, created_at, updated_at)
				values (gen_random_uuid(), ?, 'de', 'der Hund', 'hund', 'LEARNING', 'READY', '{}', '{}', 0, now(), now())""",
				anna.id());
		topic(anna, "{\"title\":\"Akkusativ\",\"status\":\"LEARNED\"}");
		topic(anna, "{\"title\":\"Genitiv\"}");
		UUID id = topic(anna, "{\"title\":\"Dativ\",\"cefrLevel\":\"A2\",\"description\":\"Wem?\"}");

		requestExplanation(anna, id);
		worker.runDueJobs();

		var call = fake().calls()
			.stream()
			.filter(c -> c.options().useCase() == AiUseCase.GRAMMAR_EXPLANATION)
			.findFirst()
			.orElseThrow();
		assertThat(call.prompt().system()).contains("teacher of German grammar").contains("Write the explanation in English")
			.contains("with its translation into English");
		assertThat(call.prompt().user()).contains("<topic>Dativ</topic>")
			.contains("<description>Wem?</description>")
			.contains("at level A2")
			// Learned topics may be referred to; unlearned ones aren't listed
			.contains("- Akkusativ")
			.doesNotContain("Genitiv")
			.contains("- der Hund");
	}

	@Test
	void onlyTheOwnerCanAskForAnExplanation() throws Exception {
		UUID id = topic(anna, "{\"title\":\"Dativ\"}");
		OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.POST, "/api/grammar-topics/{id}/explanation", id,
				anna.accessToken(), ben.accessToken());
	}

	@Test
	void anInvalidAnswerFailsTheJobAndLeavesTheTopicAsItWas() throws Exception {
		UUID id = topic(anna, "{\"title\":\"Dativ\"}");
		// No examples: Bean Validation wants at least two, and the retry gets the same answer
		answer.set(JSON.writeValueAsString(Map.of("summary", "x", "rules", List.of("y"), "examples", List.of(),
				"commonMistakes", List.of())));
		String jobId = requestExplanation(anna, id).get("aiJobId").asString();

		worker.runDueJobs();

		mvc.perform(get("/api/ai/jobs/{id}", jobId).header("Authorization", anna.bearer()))
			.andExpect(jsonPath("$.status").value("FAILED"));
		assertThat(topicJson(anna, id).get("aiExplanation").isNull()).isTrue();
	}

	@Test
	void aNewExplanationReplacesTheOldOneAndDeletedTopicsAreSkipped() throws Exception {
		UUID id = topic(anna, "{\"title\":\"Dativ\"}");
		requestExplanation(anna, id);
		worker.runDueJobs();

		answer.set(JSON.writeValueAsString(Map.of("summary", "Second version.", "rules", List.of("Rule"), "examples",
				List.of(Map.of("sentence", "Mit dem Rad.", "translation", "By bike."),
						Map.of("sentence", "Bei der Arbeit.", "translation", "At work.")),
				"commonMistakes", List.of())));
		assertThat(requestExplanation(anna, id).get("alreadyRunning").asBoolean()).isFalse();
		worker.runDueJobs();
		assertThat(topicJson(anna, id).at("/aiExplanation/summary").asString()).isEqualTo("Second version.");

		UUID gone = topic(anna, "{\"title\":\"Genitiv\"}");
		String jobId = requestExplanation(anna, gone).get("aiJobId").asString();
		mvc.perform(delete("/api/grammar-topics/{id}", gone).header("Authorization", anna.bearer()))
			.andExpect(status().isNoContent());
		int calls = fake().calls().size();
		worker.runDueJobs();
		assertThat(fake().calls()).hasSize(calls);
		mvc.perform(get("/api/ai/jobs/{id}", jobId).header("Authorization", anna.bearer()))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.result.skipped").value("the topic was deleted"));
	}

}
