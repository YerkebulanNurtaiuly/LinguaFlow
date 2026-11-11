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

	/** Per-word changes to the scripted answers, e.g. a different translation the second time. */
	private final Map<String, Map<String, Object>> overrides = new java.util.concurrent.ConcurrentHashMap<>();

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
			case "laufen" -> {
				word.putAll(Map.of("partOfSpeech", "VERB", "translation", "to run", "cefrLevel", "A1", "verbForms",
						Map.of("praeteritum", "lief", "partizip2", "gelaufen", "auxiliary", "SEIN")));
				word.put("plural", null);
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
		word.putIfAbsent("verbForms", null);
		word.put("examples", examples);
		word.put("notes", null);
		word.putAll(overrides.getOrDefault(lemma, Map.of()));
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

	// --- VOC-06: ask again, and edit everything ------------------------------------

	private JsonNode word(TestUser user, UUID id) throws Exception {
		return JSON.readTree(mvc.perform(get("/api/vocabulary/{id}", id).header("Authorization", user.bearer()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString());
	}

	private org.springframework.test.web.servlet.ResultActions edit(TestUser user, UUID id, String body)
			throws Exception {
		return mvc.perform(patch("/api/vocabulary/{id}", id).header("Authorization", user.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	@Test
	void askingAgainReplacesOnlyWhatTheAiFilledInWithAFreshAnswer() throws Exception {
		UUID id = add(anna,
				"{\"lemma\":\"Hund\",\"notes\":\"Mein Nachbar hat einen.\",\"examples\":[{\"sentence\":\"Mein Hund heißt Bello.\"}]}");
		worker.runDueJobs();
		assertThat(word(anna, id).get("examples")).hasSize(3);
		// The model would now answer differently, and the cache still holds the first answer
		overrides.put("Hund", Map.of("translation", "dog; hound"));

		mvc.perform(post("/api/vocabulary/{id}/regenerate", id).header("Authorization", anna.bearer()))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.aiJobId").exists())
			.andExpect(jsonPath("$.item.aiStatus").value("PENDING_AI"))
			.andExpect(jsonPath("$.item.translation").doesNotExist())
			.andExpect(jsonPath("$.item.plural").doesNotExist())
			// The learner's own example and notes stay, and the part of speech keeps saying which word it is
			.andExpect(jsonPath("$.item.examples.length()").value(1))
			.andExpect(jsonPath("$.item.examples[0].sentence").value("Mein Hund heißt Bello."))
			.andExpect(jsonPath("$.item.notes").value("Mein Nachbar hat einen."))
			.andExpect(jsonPath("$.item.partOfSpeech").value("NOUN"));

		worker.runDueJobs();

		JsonNode again = word(anna, id);
		assertThat(again.get("aiStatus").asString()).isEqualTo("READY");
		assertThat(again.get("translation").asString()).isEqualTo("dog; hound");
		assertThat(again.get("plural").asString()).isEqualTo("Hunde");
		assertThat(again.get("examples")).hasSize(3);
		assertThat(again.at("/examples/0/aiGenerated").asBoolean()).isFalse();
		assertThat(again.at("/examples/1/aiGenerated").asBoolean()).isTrue();
		assertThat(enrichmentCalls()).isEqualTo(2);
	}

	@Test
	void askingAgainKeepsTheLearnersValuesAndWaitsForARunningJob() throws Exception {
		UUID id = add(anna, "{\"lemma\":\"Katze\",\"translation\":\"kitty\"}");
		mvc.perform(post("/api/vocabulary/{id}/regenerate", id).header("Authorization", anna.bearer()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CONFLICT"));
		worker.runDueJobs();

		mvc.perform(post("/api/vocabulary/{id}/regenerate", id).header("Authorization", anna.bearer()))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.item.translation").value("kitty"));
		worker.runDueJobs();
		com.linguaflow.testsupport.OwnershipAssertions.assertOnlyOwnerCanAccess(mvc,
				org.springframework.http.HttpMethod.POST, "/api/vocabulary/{id}/regenerate", id, anna.accessToken(),
				ben.accessToken());
	}

	@Test
	void examplesCanBeEditedAndKeepTheirAiFlagOnlyWhenUnchanged() throws Exception {
		UUID id = add(anna, "{\"lemma\":\"Hund\"}");
		worker.runDueJobs();
		JsonNode examples = word(anna, id).get("examples");
		String first = examples.get(0).get("id").asString();
		String second = examples.get(1).get("id").asString();

		edit(anna, id, """
				{"examples":[
				  {"id":"%s","sentence":"Der Hund bellt.","translation":"The dog barks."},
				  {"id":"%s","sentence":"Ich habe zwei Hunde.","translation":"I have two dogs."},
				  {"sentence":"  Hunde sind treu. ","translation":""}
				]}""".formatted(first, second))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.examples.length()").value(3))
			// Sent back unchanged: still the AI's
			.andExpect(jsonPath("$.examples[0].sentence").value("Der Hund bellt."))
			.andExpect(jsonPath("$.examples[0].aiGenerated").value(true))
			// Edited or new: the learner's
			.andExpect(jsonPath("$.examples[1].aiGenerated").value(false))
			.andExpect(jsonPath("$.examples[2].sentence").value("Hunde sind treu."))
			.andExpect(jsonPath("$.examples[2].translation").doesNotExist());

		edit(anna, id, "{\"examples\":[]}").andExpect(jsonPath("$.examples").isEmpty());
		edit(anna, id, "{\"examples\":[{\"sentence\":\" \"}]}").andExpect(status().isBadRequest());
	}

	@Test
	void verbFormsCanBeSetAndClearedOnVerbsOnly() throws Exception {
		UUID id = add(anna, "{\"lemma\":\"laufen\",\"partOfSpeech\":\"VERB\"}");

		edit(anna, id, "{\"verbForms\":{\"praeteritum\":\"lief\",\"partizip2\":\"gelaufen\",\"auxiliary\":\"SEIN\"}}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.extra.praeteritum").value("lief"))
			.andExpect(jsonPath("$.extra.auxiliary").value("SEIN"));
		edit(anna, id, "{\"clear\":[\"VERB_FORMS\"]}").andExpect(jsonPath("$.extra.praeteritum").doesNotExist());

		UUID noun = add(anna, "{\"lemma\":\"Tisch\",\"partOfSpeech\":\"NOUN\"}");
		edit(anna, noun, "{\"verbForms\":{\"praeteritum\":\"x\",\"partizip2\":\"y\",\"auxiliary\":\"HABEN\"}}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("verbForms"));

		// No longer a verb: its verb forms go
		edit(anna, id, "{\"verbForms\":{\"praeteritum\":\"lief\",\"partizip2\":\"gelaufen\",\"auxiliary\":\"SEIN\"}}");
		edit(anna, id, "{\"partOfSpeech\":\"NOUN\"}").andExpect(jsonPath("$.extra.praeteritum").doesNotExist());
	}

	@Test
	void choicesCanBeClearedButNotSetAndClearedAtOnce() throws Exception {
		UUID id = add(anna, "{\"lemma\":\"Hund\",\"partOfSpeech\":\"NOUN\",\"gender\":\"MASCULINE\",\"cefrLevel\":\"A1\"}");

		edit(anna, id, "{\"clear\":[\"GENDER\",\"CEFR_LEVEL\",\"PART_OF_SPEECH\"]}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.partOfSpeech").doesNotExist())
			.andExpect(jsonPath("$.gender").doesNotExist())
			.andExpect(jsonPath("$.article").doesNotExist())
			.andExpect(jsonPath("$.cefrLevel").doesNotExist());

		edit(anna, id, "{\"gender\":\"FEMININE\",\"clear\":[\"GENDER\"]}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("gender"));
		edit(anna, id, "{\"clear\":[\"TRANSLATION\"]}").andExpect(status().isBadRequest());
	}

	@Test
	void editedFieldsStopCountingAsAiGenerated() throws Exception {
		UUID id = add(anna, "{\"lemma\":\"laufen\"}");
		worker.runDueJobs();
		assertThat(word(anna, id).get("aiGeneratedFields").toString()).contains("verbForms");

		edit(anna, id, "{\"verbForms\":{\"praeteritum\":\"lief\",\"partizip2\":\"gerannt\",\"auxiliary\":\"SEIN\"},"
				+ "\"notes\":\"\"}")
			.andExpect(jsonPath("$.aiGeneratedFields", not(hasItem("verbForms"))))
			.andExpect(jsonPath("$.aiGeneratedFields", not(hasItem("notes"))))
			.andExpect(jsonPath("$.extra.partizip2").value("gerannt"));
	}

}
