package com.linguaflow.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.ai.AiUseCase;
import com.linguaflow.ai.FakeLlmClient;
import com.linguaflow.ai.LlmClient;
import com.linguaflow.ai.LlmException;
import com.linguaflow.ai.LlmPrompt;
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

/**
 * AI enrichment of words end to end (VOC-05): the API queues the work, the job worker
 * runs it against a scripted fake model, and the word comes back filled in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class VocabularyEnrichmentTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Pattern BATCH_WORDS = Pattern.compile("<words>\\n(.*?)\\n</words>", Pattern.DOTALL);

	private static final Pattern SINGLE_WORD = Pattern.compile("<word>(.*?)</word>");

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

	private TestUser anna;

	private TestUser ben;

	/** Words whose answers are broken on purpose. */
	private final Set<String> broken = new CopyOnWriteArraySet<>();

	/** When set, every batch answer leaves out the last entry. */
	private volatile boolean batchesDropAWord;

	private FakeLlmClient fake() {
		return (FakeLlmClient) client;
	}

	@BeforeEach
	void setUp() {
		users.deleteAll();
		jdbc.update("delete from ai_cache");
		jdbc.update("delete from ai_jobs");
		fake().reset();
		fake().respondTo(AiUseCase.VOCABULARY_ENRICHMENT, this::answer);
		TestUsers testUsers = new TestUsers(registration, accessTokens, jdbc);
		anna = testUsers.learner("anna@example.de");
		ben = testUsers.learner("ben@example.de");
	}

	@AfterEach
	void tearDown() {
		fake().reset();
	}

	/** A small dictionary standing in for the model; answers batch and single prompts. */
	private String answer(LlmPrompt prompt) {
		Matcher batch = BATCH_WORDS.matcher(prompt.user());
		if (batch.find()) {
			List<Object> entries = new ArrayList<>(
					batch.group(1).lines().map(line -> line.replaceFirst("^- ", "")).map(this::entry).toList());
			if (batchesDropAWord) {
				entries.removeLast();
			}
			return JSON.writeValueAsString(Map.of("words", entries));
		}
		Matcher single = SINGLE_WORD.matcher(prompt.user());
		single.find();
		return JSON.writeValueAsString(entry(single.group(1)));
	}

	private Map<String, Object> entry(String line) {
		String lemma = line.replaceFirst(" \\(\\w+\\)$", "").replaceFirst("^(der|die|das) ", "");
		Map<String, Object> word = new LinkedHashMap<>();
		word.put("word", line);
		List<Map<String, String>> examples = List.of(Map.of("sentence", "Der " + lemma + " ist hier.", "translation",
				"The " + lemma + " is here."), Map.of("sentence", "Ich sehe den " + lemma + ".", "translation",
						"I see the " + lemma + "."), Map.of("sentence", "Wo ist der " + lemma + "?", "translation",
								"Where is the " + lemma + "?"));
		switch (lemma) {
			case "Hund" -> {
				word.putAll(Map.of("partOfSpeech", "NOUN", "gender", "MASCULINE", "plural", "Hunde", "translation",
						"dog", "cefrLevel", "A1"));
				examples = List.of(Map.of("sentence", "Der Hund bellt.", "translation", "The dog barks."),
						Map.of("sentence", "Ich habe einen Hund.", "translation", "I have a dog."),
						Map.of("sentence", "Der Hund schläft im Garten.", "translation", "The dog sleeps in the garden."));
			}
			case "Katze" -> word.putAll(Map.of("partOfSpeech", "NOUN", "gender", "FEMININE", "plural", "Katzen",
					"translation", "cat", "cefrLevel", "A1"));
			default -> word.putAll(Map.of("partOfSpeech", "NOUN", "gender", "NEUTER", "plural", lemma + "e",
					"translation", "t-" + lemma, "cefrLevel", "A2"));
		}
		if (broken.contains(lemma)) {
			// A German noun without a gender fails the domain check
			word.put("gender", null);
		}
		word.putIfAbsent("gender", null);
		word.put("verbForms", null);
		word.put("examples", examples);
		word.put("notes", null);
		return word;
	}

	private int enrichmentCalls() {
		return (int) fake().calls()
			.stream()
			.filter(call -> call.options().useCase() == AiUseCase.VOCABULARY_ENRICHMENT)
			.count();
	}

	private UUID add(TestUser user, String body) throws Exception {
		String response = mvc
			.perform(post("/api/vocabulary").header("Authorization", user.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.aiStatus").value("PENDING_AI"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JSON.readTree(response).get("id").asString());
	}

	private String aiStatus(UUID id) {
		return jdbc.queryForObject("select ai_status from vocabulary_items where id = ?", String.class, id);
	}

	/** VOC-05's "done when": adding "Hund" yields "der Hund, die Hunde, dog" with examples. */
	@Test
	void addingHundYieldsDerHundDieHundeDogWithExamples() throws Exception {
		UUID id = add(anna, "{\"lemma\":\"Hund\"}");
		assertThat(jdbc.queryForObject("select count(*) from ai_jobs where type = 'VOCABULARY_ENRICHMENT'",
				Integer.class)).isEqualTo(1);

		worker.runDueJobs();

		mvc.perform(get("/api/vocabulary/{id}", id).header("Authorization", anna.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.lemma").value("Hund"))
			.andExpect(jsonPath("$.article").value("der"))
			.andExpect(jsonPath("$.partOfSpeech").value("NOUN"))
			.andExpect(jsonPath("$.gender").value("MASCULINE"))
			.andExpect(jsonPath("$.plural").value("Hunde"))
			.andExpect(jsonPath("$.translation").value("dog"))
			.andExpect(jsonPath("$.cefrLevel").value("A1"))
			.andExpect(jsonPath("$.aiStatus").value("READY"))
			.andExpect(jsonPath("$.examples.length()").value(3))
			.andExpect(jsonPath("$.examples[0].sentence").value("Der Hund bellt."))
			.andExpect(jsonPath("$.examples[0].translation").value("The dog barks."))
			.andExpect(jsonPath("$.aiGeneratedFields",
					hasItems("partOfSpeech", "translation", "gender", "plural", "cefrLevel")));
		// The prompt carried the word as data, and the call was logged against Anna
		assertThat(fake().calls().getFirst().prompt().user()).contains("- Hund");
		assertThat(jdbc.queryForObject("select count(*) from ai_requests where user_id = ?", Integer.class, anna.id()))
			.isEqualTo(1);
	}

	@Test
	void aBulkAddIsEnrichedInBatchesOfTwenty() throws Exception {
		String items = IntStream.rangeClosed(1, 45)
			.mapToObj(i -> "{\"lemma\":\"Wort" + i + "\"}")
			.reduce((a, b) -> a + "," + b)
			.orElseThrow();

		String response = mvc
			.perform(post("/api/vocabulary/bulk/confirm").header("Authorization", anna.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"items\":[" + items + "]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.createdCount").value(45))
			.andExpect(jsonPath("$.aiJobIds.length()").value(3))
			.andReturn()
			.getResponse()
			.getContentAsString();

		worker.runDueJobs();

		assertThat(enrichmentCalls()).isEqualTo(3);
		assertThat(jdbc.queryForObject("select count(*) from vocabulary_items where user_id = ? and ai_status = 'READY'",
				Integer.class, anna.id())).isEqualTo(45);
		// The UI can follow the batches (AI-08)
		JsonNode jobIds = JSON.readTree(response).get("aiJobIds");
		var progress = get("/api/ai/jobs/progress").header("Authorization", anna.bearer());
		jobIds.forEach(id -> progress.param("id", id.asString()));
		mvc.perform(progress).andExpect(jsonPath("$.succeeded").value(3)).andExpect(jsonPath("$.finished").value(true));
	}

	@Test
	void anotherLearnerGetsTheSameWordFromTheCache() throws Exception {
		add(anna, "{\"lemma\":\"Hund\"}");
		worker.runDueJobs();

		UUID bens = add(ben, "{\"lemma\":\"der Hund\"}");
		worker.runDueJobs();

		assertThat(aiStatus(bens)).isEqualTo("READY");
		assertThat(enrichmentCalls()).isEqualTo(1);
		mvc.perform(get("/api/vocabulary/{id}", bens).header("Authorization", ben.bearer()))
			.andExpect(jsonPath("$.translation").value("dog"))
			.andExpect(jsonPath("$.plural").value("Hunde"));
	}

	@Test
	void whatTheLearnerGaveOrEditsMeanwhileIsKept() throws Exception {
		UUID katze = add(anna, "{\"lemma\":\"Katze\",\"translation\":\"kitty\"}");
		UUID hund = add(anna, "{\"lemma\":\"Hund\"}");
		// Edited while the AI hasn't run yet
		mvc.perform(patch("/api/vocabulary/{id}", hund).header("Authorization", anna.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"plural\":\"Hündchen\"}")).andExpect(status().isOk());

		worker.runDueJobs();

		mvc.perform(get("/api/vocabulary/{id}", katze).header("Authorization", anna.bearer()))
			.andExpect(jsonPath("$.translation").value("kitty"))
			.andExpect(jsonPath("$.gender").value("FEMININE"))
			.andExpect(jsonPath("$.aiGeneratedFields", not(hasItem("translation"))));
		mvc.perform(get("/api/vocabulary/{id}", hund).header("Authorization", anna.bearer()))
			.andExpect(jsonPath("$.plural").value("Hündchen"))
			.andExpect(jsonPath("$.translation").value("dog"))
			.andExpect(jsonPath("$.aiGeneratedFields", not(hasItem("plural"))));
	}

	@Test
	void anInvalidBatchFallsBackToOneWordAtATime() throws Exception {
		batchesDropAWord = true;
		broken.add("Kaputt");
		String items = List.of("Hund", "Katze", "Kaputt")
			.stream()
			.map(word -> "{\"lemma\":\"" + word + "\"}")
			.reduce((a, b) -> a + "," + b)
			.orElseThrow();
		mvc.perform(post("/api/vocabulary/bulk/confirm").header("Authorization", anna.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"items\":[" + items + "]}")).andExpect(status().isOk());

		worker.runDueJobs();

		Map<String, String> statuses = new LinkedHashMap<>();
		jdbc.query("select lemma, ai_status from vocabulary_items where user_id = ?",
				rs -> { statuses.put(rs.getString(1), rs.getString(2)); }, anna.id());
		assertThat(statuses).containsEntry("Hund", "READY").containsEntry("Katze", "READY").containsEntry("Kaputt",
				"AI_FAILED");
		// The job itself completed: one bad word doesn't fail the batch
		assertThat(jdbc.queryForObject("select status from ai_jobs", String.class)).isEqualTo("SUCCEEDED");
		// A broken word's answer is never shared through the cache
		assertThat(jdbc.queryForObject("select count(*) from ai_cache", Integer.class)).isEqualTo(2);
	}

	@Test
	void wordsAreMarkedFailedWhenTheJobGivesUp() throws Exception {
		fake().failNext(new LlmException(LlmException.Kind.REFUSED, "refused"));
		UUID id = add(anna, "{\"lemma\":\"Hund\"}");

		worker.runDueJobs();

		assertThat(aiStatus(id)).isEqualTo("AI_FAILED");
		assertThat(jdbc.queryForObject("select status from ai_jobs", String.class)).isEqualTo("FAILED");
	}

	@Test
	void wordsDeletedBeforeTheJobRunsAreSkipped() throws Exception {
		UUID id = add(anna, "{\"lemma\":\"Hund\"}");
		mvc.perform(delete("/api/vocabulary/{id}", id).header("Authorization", anna.bearer()))
			.andExpect(status().isNoContent());

		worker.runDueJobs();

		assertThat(enrichmentCalls()).isZero();
		assertThat(jdbc.queryForObject("select status from ai_jobs", String.class)).isEqualTo("SUCCEEDED");
	}

}
