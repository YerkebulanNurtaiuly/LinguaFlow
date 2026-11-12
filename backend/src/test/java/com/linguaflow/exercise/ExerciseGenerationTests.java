package com.linguaflow.exercise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.ai.AiUseCase;
import com.linguaflow.ai.FakeLlmClient;
import com.linguaflow.ai.LlmClient;
import com.linguaflow.ai.LlmPrompt;
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

/** AI exercise generation end to end (EX-02), against a scripted fake model. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ExerciseGenerationTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Pattern COUNT = Pattern.compile("Write (\\d+) exercises");

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

	/** Anna's words: lemma → ID. */
	private final Map<String, UUID> words = new java.util.LinkedHashMap<>();

	private final AtomicInteger sentence = new AtomicInteger();

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
		words.clear();
		word("der Hund", "hund", "dog", "LEARNING");
		word("die Katze", "katze", "cat", "LEARNING");
		word("das Kind", "kind", "child", "NEW");
		word("helfen", "helfen", "to help", "LEARNED");
		// Not enriched yet: never offered to the model
		jdbc.update("insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma, translation) "
				+ "values (?, ?, 'de', 'der Vogel', 'vogel', 'bird')", UUID.randomUUID(), anna.id());
	}

	@AfterEach
	void tearDown() {
		fake().reset();
	}

	private void word(String lemma, String normalized, String translation, String status) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma, translation, "
				+ "status, ai_status) values (?, ?, 'de', ?, ?, ?, ?, 'READY')", id, anna.id(), lemma, normalized,
				translation, status);
		words.put(lemma, id);
	}

	private UUID topic(TestUser user, String title) throws Exception {
		return UUID.fromString(json(mvc
			.perform(post("/api/grammar-topics").header("Authorization", user.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"" + title + "\",\"cefrLevel\":\"A2\",\"description\":\"Indirect objects\"}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString()).get("id").asString());
	}

	private JsonNode requestSet(TestUser user, String body) throws Exception {
		return json(mvc
			.perform(post("/api/exercise-sets").header("Authorization", user.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString());
	}

	private JsonNode set(TestUser user, String id) throws Exception {
		return json(mvc.perform(get("/api/exercise-sets/{id}", id).header("Authorization", user.bearer()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString());
	}

	private static JsonNode json(String body) {
		return JSON.readTree(body);
	}

	private static int requested(LlmPrompt prompt) {
		Matcher matcher = COUNT.matcher(prompt.user());
		assertThat(matcher.find()).as("count in the prompt").isTrue();
		return Integer.parseInt(matcher.group(1));
	}

	private static String batch(List<Map<String, Object>> items) {
		return JSON.writeValueAsString(Map.of("exercises", items));
	}

	private Map<String, Object> fillBlank(String word) {
		int n = sentence.incrementAndGet();
		return Map.of("prompt", Map.of("text", "Ich gebe " + word + " Nummer " + n + " ___ Wasser."), "solution",
				Map.of("blanks", List.of(Map.of("answer", "das", "alternatives", List.of())), "explanation",
						"Geben takes the Dativ."),
				"words", List.of(word), "topics", List.of("Dativ"));
	}

	private Map<String, Object> multipleChoice(String word) {
		int n = sentence.incrementAndGet();
		return Map.of("prompt",
				Map.of("question", "Ich helfe ___ " + word + " " + n + ".", "options", List.of("der", "dem", "den")),
				"solution", Map.of("correctIndex", 1), "words", List.of(word + " (" + "translation)", "helfen"),
				"topics", List.of("dativ"));
	}

	/** Answers each format with valid exercises, cycling through Anna's words. */
	private Function<LlmPrompt, String> validAnswers() {
		List<String> lemmas = List.of("der Hund", "die Katze", "das Kind");
		return prompt -> {
			List<Map<String, Object>> items = new ArrayList<>();
			for (int i = 0; i < requested(prompt); i++) {
				String lemma = lemmas.get(i % lemmas.size());
				items.add(prompt.system().contains("Format: multiple choice.") ? multipleChoice(lemma)
						: fillBlank(lemma));
			}
			return batch(items);
		};
	}

	@Test
	void aDativSetPractisesTheLearnersOwnWords() throws Exception {
		fake().respondTo(AiUseCase.EXERCISE_GENERATION, validAnswers());
		UUID dativ = topic(anna, "Dativ");

		JsonNode created = requestSet(anna, """
				{"grammarTopicIds":["%s"],"types":["FILL_BLANK","MULTIPLE_CHOICE"],"count":10,"difficulty":"EASY"}
				""".formatted(dativ));
		String setId = created.get("exerciseSet").get("id").asString();
		assertThat(created.get("exerciseSet").get("status").asString()).isEqualTo("GENERATING");
		assertThat(created.get("aiJobId").asString()).isNotBlank();

		worker.runDueJobs();

		JsonNode set = set(anna, setId);
		assertThat(set.get("status").asString()).isEqualTo("READY");
		JsonNode exercises = set.get("exercises");
		assertThat(exercises).hasSize(10);
		// Types alternate, five of each
		for (int i = 0; i < 10; i++) {
			assertThat(exercises.get(i).get("position").asInt()).isEqualTo(i);
			assertThat(exercises.get(i).get("type").asString()).isEqualTo(i % 2 == 0 ? "FILL_BLANK" : "MULTIPLE_CHOICE");
			assertThat(exercises.get(i).get("difficulty").asString()).isEqualTo("EASY");
			assertThat(exercises.get(i).get("grammarTopicIds").get(0).asString()).isEqualTo(dativ.toString());
			assertThat(exercises.get(i).has("solution")).as("solutions stay hidden").isFalse();
		}
		assertThat(exercises.get(0).get("prompt").get("text").asString()).contains("___");
		assertThat(exercises.get(1).get("prompt").get("options")).hasSize(3);

		// Every exercise uses Anna's own words, matched back to them
		Set<UUID> used = new HashSet<>();
		exercises.forEach(exercise -> exercise.get("vocabularyItemIds")
			.forEach(id -> used.add(UUID.fromString(id.asString()))));
		assertThat(used).containsOnly(words.get("der Hund"), words.get("die Katze"), words.get("das Kind"),
				words.get("helfen"));
		exercises.forEach(exercise -> assertThat(exercise.get("vocabularyItemIds")).isNotEmpty());

		// One call per type, built from her material
		List<FakeLlmClient.Call> calls = fake().calls();
		assertThat(calls).hasSize(2);
		String prompt = calls.get(0).prompt().user();
		assertThat(prompt).contains("Write 5 exercises for a learner at level A2.")
			.contains("Difficulty: easy")
			.contains("- Dativ: Indirect objects")
			.contains("- der Hund (dog)")
			.contains("- die Katze (cat)")
			.contains("- das Kind (child)")
			.doesNotContain("Vogel");
		// Words she's still learning are the practice words; the others are known words
		assertThat(prompt.substring(prompt.indexOf("<practice_vocabulary>"), prompt.indexOf("</practice_vocabulary>")))
			.contains("der Hund");
		assertThat(calls.get(0).jsonSchema()).isNotNull();
	}

	@Test
	void invalidAndRepeatedExercisesAreDroppedAndToppedUp() throws Exception {
		AtomicInteger call = new AtomicInteger();
		fake().respondTo(AiUseCase.EXERCISE_GENERATION, prompt -> {
			if (call.incrementAndGet() == 1) {
				Map<String, Object> good = fillBlank("der Hund");
				Map<String, Object> noGap = Map.of("prompt", Map.of("text", "Ich gebe dem Hund Wasser."), "solution",
						Map.of("blanks", List.of(Map.of("answer", "dem", "alternatives", List.of()))), "words",
						List.of(), "topics", List.of());
				Map<String, Object> blankAnswer = Map.of("prompt", Map.of("text", "Ich ___ dir."), "solution",
						Map.of("blanks", List.of(Map.of("answer", " ", "alternatives", List.of()))), "words",
						List.of(), "topics", List.of());
				return batch(List.of(good, noGap, good, blankAnswer));
			}
			assertThat(requested(prompt)).isEqualTo(2);
			assertThat(prompt.user()).contains("<recent_exercises>").contains("Ich gebe der Hund Nummer 1 ___ Wasser.");
			return batch(List.of(fillBlank("die Katze"), fillBlank("das Kind")));
		});
		UUID dativ = topic(anna, "Dativ");
		String setId = requestSet(anna, """
				{"grammarTopicIds":["%s"],"types":["FILL_BLANK"],"count":3}""".formatted(dativ))
			.get("exerciseSet")
			.get("id")
			.asString();

		worker.runDueJobs();

		JsonNode set = set(anna, setId);
		assertThat(set.get("status").asString()).isEqualTo("READY");
		assertThat(set.get("exercises")).hasSize(3);
		assertThat(call.get()).isEqualTo(2);
		assertThat(jdbc.queryForObject("select result ->> 'dropped' from ai_jobs where user_id = ?", String.class,
				anna.id()))
			.isEqualTo("3");
	}

	@Test
	void aSetWithNoUsableExerciseFailsAfterThreeCalls() throws Exception {
		fake().respondTo(AiUseCase.EXERCISE_GENERATION, prompt -> batch(List.of(Map.of("prompt",
				Map.of("question", "Welche?", "options", List.of("der", "der")), "solution", Map.of("correctIndex", 5),
				"words", List.of(), "topics", List.of()))));
		UUID dativ = topic(anna, "Dativ");
		String setId = requestSet(anna, """
				{"grammarTopicIds":["%s"],"types":["MULTIPLE_CHOICE"],"count":2}""".formatted(dativ))
			.get("exerciseSet")
			.get("id")
			.asString();

		worker.runDueJobs();

		assertThat(set(anna, setId).get("status").asString()).isEqualTo("FAILED");
		assertThat(fake().calls()).hasSize(ExerciseGenerator.ROUNDS);
	}

	@Test
	void laterSetsAreToldNotToRepeatEarlierExercises() throws Exception {
		fake().respondTo(AiUseCase.EXERCISE_GENERATION, validAnswers());
		UUID dativ = topic(anna, "Dativ");
		String body = """
				{"grammarTopicIds":["%s"],"types":["FILL_BLANK"],"count":2}""".formatted(dativ);
		requestSet(anna, body);
		worker.runDueJobs();
		requestSet(anna, body);
		worker.runDueJobs();

		assertThat(fake().calls()).hasSize(2);
		assertThat(fake().calls().get(0).prompt().user()).doesNotContain("<recent_exercises>");
		assertThat(fake().calls().get(1).prompt().user()).contains("<recent_exercises>")
			.contains("Ich gebe der Hund Nummer 1 ___ Wasser.");
	}

	@Test
	void typesDefaultToThePreferredOnesAndCountToTen() throws Exception {
		fake().respondTo(AiUseCase.EXERCISE_GENERATION, validAnswers());
		jdbc.update("update user_languages set preferred_exercise_types = '{MULTIPLE_CHOICE}' where user_id = ?",
				anna.id());
		UUID dativ = topic(anna, "Dativ");

		JsonNode request = requestSet(anna, "{\"grammarTopicIds\":[\"%s\"]}".formatted(dativ)).get("exerciseSet")
			.get("request");

		assertThat(request.get("types")).hasSize(1);
		assertThat(request.get("types").get(0).asString()).isEqualTo("MULTIPLE_CHOICE");
		assertThat(request.get("count").asInt()).isEqualTo(10);
		assertThat(request.path("difficulty").isNull() || request.path("difficulty").isMissingNode()).isTrue();
	}

	@Test
	void topicsMustBeTheLearnersOwnAndCountsAreLimited() throws Exception {
		UUID bens = topic(ben, "Dativ");
		UUID dativ = topic(anna, "Dativ");
		mvc.perform(post("/api/exercise-sets").header("Authorization", anna.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"grammarTopicIds\":[\"%s\"]}".formatted(bens)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("grammarTopicIds"));
		mvc.perform(post("/api/exercise-sets").header("Authorization", anna.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"grammarTopicIds\":[]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("grammarTopicIds"));
		mvc.perform(post("/api/exercise-sets").header("Authorization", anna.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"grammarTopicIds\":[\"%s\"],\"count\":21}".formatted(dativ)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("count"));
		assertThat(jdbc.queryForObject("select count(*) from exercise_sets", Long.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from ai_jobs", Long.class)).isZero();
	}

	@Test
	void setsAreListedWithTheirCountsAndOnlyTheOwnerSeesThem() throws Exception {
		fake().respondTo(AiUseCase.EXERCISE_GENERATION, validAnswers());
		UUID dativ = topic(anna, "Dativ");
		String first = requestSet(anna, """
				{"grammarTopicIds":["%s"],"types":["FILL_BLANK"],"count":3}""".formatted(dativ))
			.get("exerciseSet")
			.get("id")
			.asString();
		worker.runDueJobs();
		String second = requestSet(anna, """
				{"grammarTopicIds":["%s"],"types":["FILL_BLANK"],"count":2}""".formatted(dativ))
			.get("exerciseSet")
			.get("id")
			.asString();

		mvc.perform(get("/api/exercise-sets").header("Authorization", anna.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.content[0].id").value(second))
			.andExpect(jsonPath("$.content[0].status").value("GENERATING"))
			.andExpect(jsonPath("$.content[0].exerciseCount").value(0))
			.andExpect(jsonPath("$.content[1].exerciseCount").value(3));
		mvc.perform(get("/api/exercise-sets").header("Authorization", ben.bearer()))
			.andExpect(jsonPath("$.totalElements").value(0));
		mvc.perform(get("/api/exercise-sets?sort=status").header("Authorization", anna.bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("sort"));

		OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.GET, "/api/exercise-sets/{id}",
				UUID.fromString(first), anna.accessToken(), ben.accessToken());
		OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.DELETE, "/api/exercise-sets/{id}",
				UUID.fromString(first), anna.accessToken(), ben.accessToken());
		mvc.perform(get("/api/exercise-sets/{id}", first).header("Authorization", anna.bearer()))
			.andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("select count(*) from exercises where exercise_set_id = ?::uuid", Long.class,
				first))
			.isZero();
	}

	@Test
	void aSetDeletedWhileGeneratingIsSkipped() throws Exception {
		fake().respondTo(AiUseCase.EXERCISE_GENERATION, validAnswers());
		UUID dativ = topic(anna, "Dativ");
		String setId = requestSet(anna, """
				{"grammarTopicIds":["%s"],"types":["FILL_BLANK"],"count":2}""".formatted(dativ))
			.get("exerciseSet")
			.get("id")
			.asString();
		mvc.perform(delete("/api/exercise-sets/{id}", setId).header("Authorization", anna.bearer()))
			.andExpect(status().isNoContent());

		worker.runDueJobs();

		assertThat(fake().calls()).isEmpty();
		assertThat(jdbc.queryForObject("select status from ai_jobs where user_id = ?", String.class, anna.id()))
			.isEqualTo("SUCCEEDED");
	}


	private UUID lesson(String title, String date, String... lemmas) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into study_sessions (id, user_id, language_code, title, studied_on) "
				+ "values (?, ?, 'de', ?, ?::date)", id, anna.id(), title, date);
		for (String lemma : lemmas) {
			jdbc.update("insert into session_vocabulary (study_session_id, vocabulary_item_id) values (?, ?)", id,
					words.get(lemma));
		}
		return id;
	}

	/** Exercises naming both topics. */
	private Function<LlmPrompt, String> combinedAnswers() {
		return prompt -> {
			List<Map<String, Object>> items = new ArrayList<>();
			for (int i = 0; i < requested(prompt); i++) {
				int n = sentence.incrementAndGet();
				items.add(Map.of("prompt", Map.of("text", "Gestern habe ich dem Hund Nummer " + n + " ___ Wasser gegeben."),
						"solution",
						Map.of("blanks", List.of(Map.of("answer", "das", "alternatives", List.of())), "explanation",
								"Dativ object in a Perfekt sentence."),
						"words", List.of("der Hund", "die Katze"), "topics", List.of("Dativ", "Perfekt")));
			}
			return batch(items);
		};
	}

	@Test
	void combinedSetsMixTopicsAndLessons() throws Exception {
		fake().respondTo(AiUseCase.EXERCISE_GENERATION, combinedAnswers());
		UUID dativ = topic(anna, "Dativ");
		UUID perfekt = topic(anna, "Perfekt");
		lesson("Lektion 2", "2026-11-02", "der Hund", "das Kind");
		lesson("Lektion 5", "2026-11-09", "die Katze");

		JsonNode created = requestSet(anna, """
				{"grammarTopicIds":["%s","%s"],"types":["FILL_BLANK"],"count":3,"combined":true}
				""".formatted(dativ, perfekt));
		assertThat(created.get("exerciseSet").get("request").get("combined").asBoolean()).isTrue();
		worker.runDueJobs();

		String prompt = fake().calls().getFirst().prompt().user();
		assertThat(prompt).contains("Combined practice: every exercise must practise at least two of the focus topics")
			.contains("<lesson_vocabulary>")
			.contains("- Lektion 2 (2 Nov): das Kind, der Hund")
			.contains("- Lektion 5 (9 Nov): die Katze");
		JsonNode set = set(anna, created.get("exerciseSet").get("id").asString());
		assertThat(set.get("exercises")).hasSize(3)
			.allSatisfy(exercise -> assertThat(exercise.get("grammarTopicIds")).hasSize(2));
		assertThat(jdbc.queryForObject("select result ->> 'combined' from ai_jobs where user_id = ?", String.class,
				anna.id()))
			.isEqualTo("3");
	}

	@Test
	void onlySetsAskedToCombineDoSo() throws Exception {
		fake().respondTo(AiUseCase.EXERCISE_GENERATION, validAnswers());
		UUID dativ = topic(anna, "Dativ");
		UUID perfekt = topic(anna, "Perfekt");
		lesson("Lektion 2", "2026-11-02", "der Hund");
		lesson("Lektion 5", "2026-11-09", "die Katze");
		requestSet(anna, """
				{"grammarTopicIds":["%s","%s"],"types":["FILL_BLANK"],"count":2}""".formatted(dativ, perfekt));
		worker.runDueJobs();
		assertThat(fake().calls().getFirst().prompt().user()).doesNotContain("Combined practice")
			.doesNotContain("lesson_vocabulary");

		// Combining needs two topics
		mvc.perform(post("/api/exercise-sets").header("Authorization", anna.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"grammarTopicIds":["%s"],"combined":true}""".formatted(dativ)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("combined"));
	}

}
