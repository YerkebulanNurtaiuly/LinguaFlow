package com.linguaflow.learning.practice;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Practice sessions end to end (PRC-02), with exercises from a scripted fake model. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class PracticeSessionApiTests {

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

	/** Anna's words, each with a card per direction. */
	private final List<UUID> words = new ArrayList<>();

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
		// Fill-in-the-blank only, so every generation call has the same format
		jdbc.update("update user_languages set preferred_exercise_types = '{FILL_BLANK}' where user_id = ?", anna.id());
		words.clear();
		String[][] lexicon = { { "der Hund", "hund", "dog" }, { "die Katze", "katze", "cat" },
				{ "das Kind", "kind", "child" }, { "der Tisch", "tisch", "table" }, { "die Lampe", "lampe", "lamp" },
				{ "das Buch", "buch", "book" } };
		for (String[] word : lexicon) {
			UUID id = UUID.randomUUID();
			jdbc.update("insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma, "
					+ "translation, part_of_speech, gender, ai_status) values (?, ?, 'de', ?, ?, ?, 'NOUN', "
					+ "'MASCULINE', 'READY')", id, anna.id(), word[0], word[1], word[2]);
			for (String direction : List.of("TARGET_TO_NATIVE", "NATIVE_TO_TARGET")) {
				UUID card = UUID.randomUUID();
				jdbc.update("insert into flashcards (id, user_id, vocabulary_item_id, direction) values (?, ?, ?, ?)",
						card, anna.id(), id, direction);
				jdbc.update("insert into srs_state (flashcard_id, user_id) values (?, ?)", card, anna.id());
			}
			words.add(id);
		}
	}

	@AfterEach
	void tearDown() {
		fake().reset();
	}

	private UUID topic(TestUser user, String title) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into grammar_topics (id, user_id, language_code, title, normalized_title) "
				+ "values (?, ?, 'de', ?, ?)", id, user.id(), title, title.toLowerCase());
		return id;
	}

	private ResultActions start(TestUser user, String body) throws Exception {
		return mvc.perform(post("/api/practice-sessions").header("Authorization", user.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private JsonNode started(TestUser user, String body) throws Exception {
		return json(start(user, body).andExpect(status().isCreated()));
	}

	private JsonNode session(TestUser user, String id) throws Exception {
		return json(mvc.perform(get("/api/practice-sessions/{id}", id).header("Authorization", user.bearer()))
			.andExpect(status().isOk()));
	}

	private ResultActions review(TestUser user, String cardId, String grade, String sessionId) throws Exception {
		return mvc.perform(post("/api/reviews").header("Authorization", user.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"flashcardId":"%s","grade":"%s","reviewToken":"%s","durationMs":3000,"practiceSessionId":"%s"}
					""".formatted(cardId, grade, UUID.randomUUID(), sessionId)));
	}

	private ResultActions answer(TestUser user, String exerciseId, String blank, String sessionId) throws Exception {
		return mvc.perform(post("/api/exercises/{id}/attempts", exerciseId).header("Authorization", user.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"blanks":["%s"],"durationMs":8000,"practiceSessionId":"%s"}
					""".formatted(blank, sessionId)));
	}

	private static JsonNode json(ResultActions result) throws Exception {
		return JSON.readTree(result.andReturn().getResponse().getContentAsString());
	}

	/** Valid fill-in-the-blank exercises, as many as each call asks for. */
	private void scriptExercises() {
		fake().respondTo(AiUseCase.EXERCISE_GENERATION, prompt -> {
			Matcher matcher = COUNT.matcher(prompt.user());
			assertThat(matcher.find()).isTrue();
			List<Map<String, Object>> items = new ArrayList<>();
			for (int i = 0; i < Integer.parseInt(matcher.group(1)); i++) {
				items.add(Map.of("prompt", Map.of("text", "Ich gebe dem Hund Nummer " + i + " ___ Wasser."),
						"solution", Map.of("blanks", List.of(Map.of("answer", "das", "alternatives", List.of())), "explanation",
								"Geben takes the Dativ."),
						"words", List.of("der Hund"), "topics", List.of("Dativ")));

			}
			return JSON.writeValueAsString(Map.of("exercises", items));
		});
	}

	private static String pattern(JsonNode items) {
		StringBuilder pattern = new StringBuilder();
		items.forEach(item -> pattern.append(item.get("type").asString().charAt(0)));
		return pattern.toString();
	}

	@Test
	void aFlashcardSessionShowsOneCardPerWord() throws Exception {
		JsonNode created = started(anna, """
				{"mode":"FULL_REVISION","length":5,"includeExercises":false}
				""");
		assertThat(created.get("aiJobId").isNull()).isTrue();
		JsonNode session = created.get("practiceSession");
		assertThat(session.get("status").asString()).isEqualTo("IN_PROGRESS");
		assertThat(session.get("mode").asString()).isEqualTo("FULL_REVISION");
		assertThat(session.get("exerciseSet").isNull()).isTrue();
		assertThat(session.get("config").get("length").asInt()).isEqualTo(5);
		assertThat(session.get("config").get("includeExercises").asBoolean()).isFalse();
		assertThat(session.get("config").get("seed").asLong()).isBetween(0L, PracticeSessionService.MAX_SEED - 1);

		JsonNode items = session.get("items");
		assertThat(items).hasSize(5);
		Set<String> seenWords = new HashSet<>();
		for (int i = 0; i < 5; i++) {
			JsonNode item = items.get(i);
			assertThat(item.get("position").asInt()).isEqualTo(i);
			assertThat(item.get("type").asString()).isEqualTo("FLASHCARD");
			assertThat(item.get("answeredAt").isNull()).isTrue();
			assertThat(item.get("exercise").isNull()).isTrue();
			JsonNode card = item.get("flashcard").get("card");
			// New words are shown for recognition first
			assertThat(card.get("direction").asString()).isEqualTo("TARGET_TO_NATIVE");
			assertThat(card.get("content").get("front").asString()).isNotBlank();
			assertThat(item.get("flashcard").get("previews").get("good").get("state").asString()).isNotBlank();
			seenWords.add(card.get("vocabularyItemId").asString());
		}
		assertThat(seenWords).hasSize(5);
		assertThat(fake().calls()).isEmpty();
	}

	@Test
	void exercisesJoinTheSessionInterleavedOnceWritten() throws Exception {
		scriptExercises();
		UUID dativ = topic(anna, "Dativ");

		JsonNode created = started(anna, """
				{"mode":"MIXED","length":9}
				""");
		assertThat(created.get("aiJobId").asString()).isNotBlank();
		JsonNode session = created.get("practiceSession");
		String id = session.get("id").asString();
		// The cards are there at once; a third of the session waits for the AI
		assertThat(pattern(session.get("items"))).isEqualTo("FFFFFF");
		assertThat(session.get("exerciseSet").get("status").asString()).isEqualTo("GENERATING");
		assertThat(session.get("exerciseSet").get("requested").asInt()).isEqualTo(3);

		// Answer the first card before the exercises arrive
		String firstCard = session.get("items").get(0).get("flashcard").get("card").get("id").asString();
		review(anna, firstCard, "GOOD", id).andExpect(status().isCreated());

		worker.runDueJobs();

		JsonNode ready = session(anna, id);
		assertThat(ready.get("exerciseSet").get("status").asString()).isEqualTo("READY");
		JsonNode items = ready.get("items");
		// The answered card stays first; the rest are interleaved
		assertThat(pattern(items)).isEqualTo("FFEFEFFEF");
		assertThat(items.get(0).get("flashcard").get("card").get("id").asString()).isEqualTo(firstCard);
		assertThat(items.get(0).get("answeredAt").isNull()).isFalse();
		JsonNode exercise = items.get(2).get("exercise");
		assertThat(exercise.get("type").asString()).isEqualTo("FILL_BLANK");
		assertThat(exercise.get("grammarTopicIds").get(0).asString()).isEqualTo(dativ.toString());
		assertThat(exercise.has("solution")).isFalse();

		// The set practises the session's words, written for this session
		String setId = ready.get("exerciseSet").get("id").asString();
		assertThat(jdbc.queryForObject("select source from exercise_sets where id = ?::uuid", String.class, setId))
			.isEqualTo("PRACTICE");
		// One topic: nothing to combine (PRC-03)
		assertThat(jdbc.queryForObject("select request ->> 'combined' from exercise_sets where id = ?::uuid", String.class,
				setId))
			.isEqualTo("false");
		assertThat(jdbc.queryForObject("select practice_session_id::text from exercise_sets where id = ?::uuid",
				String.class, setId))
			.isEqualTo(id);
		String prompt = fake().calls().getFirst().prompt().user();
		assertThat(prompt.substring(prompt.indexOf("<practice_vocabulary>"), prompt.indexOf("</practice_vocabulary>")))
			.contains("der Hund (dog)");

		// Answering the exercise in the session marks it
		answer(anna, exercise.get("id").asString(), "das", id).andExpect(status().isCreated())
			.andExpect(jsonPath("$.correct").value(true));
		assertThat(session(anna, id).get("items").get(2).get("answeredAt").isNull()).isFalse();
		assertThat(jdbc.queryForObject("select practice_session_id::text from exercise_attempts", String.class))
			.isEqualTo(id);
	}

	@Test
	void completingStoresASummary() throws Exception {
		scriptExercises();
		topic(anna, "Dativ");
		String id = started(anna, """
				{"mode":"FULL_REVISION","length":6}
				""").get("practiceSession").get("id").asString();
		worker.runDueJobs();
		JsonNode items = session(anna, id).get("items");
		assertThat(pattern(items)).isEqualTo("FEFFEF");

		String forgotten = items.get(0).get("flashcard").get("card").get("id").asString();
		String remembered = items.get(2).get("flashcard").get("card").get("id").asString();
		review(anna, forgotten, "AGAIN", id).andExpect(status().isCreated());
		review(anna, remembered, "GOOD", id).andExpect(status().isCreated());
		answer(anna, items.get(1).get("exercise").get("id").asString(), "dem", id).andExpect(status().isCreated());
		answer(anna, items.get(4).get("exercise").get("id").asString(), "das", id).andExpect(status().isCreated());

		JsonNode completed = json(mvc
			.perform(post("/api/practice-sessions/{id}/complete", id).header("Authorization", anna.bearer()))
			.andExpect(status().isOk()));
		assertThat(completed.get("status").asString()).isEqualTo("COMPLETED");
		assertThat(completed.get("completedAt").isNull()).isFalse();
		JsonNode summary = completed.get("summary");
		assertThat(summary.get("items").asInt()).isEqualTo(6);
		assertThat(summary.get("answeredItems").asInt()).isEqualTo(4);
		assertThat(summary.get("flashcards").get("items").asInt()).isEqualTo(4);
		assertThat(summary.get("flashcards").get("answered").asInt()).isEqualTo(2);
		assertThat(summary.get("flashcards").get("remembered").asInt()).isEqualTo(1);
		assertThat(summary.get("flashcards").get("again").asInt()).isEqualTo(1);
		assertThat(summary.get("flashcards").get("good").asInt()).isEqualTo(1);
		assertThat(summary.get("exercises").get("answered").asInt()).isEqualTo(2);
		assertThat(summary.get("exercises").get("correct").asInt()).isEqualTo(1);
		assertThat(summary.get("durationMs").asLong()).isEqualTo(3000 + 3000 + 8000 + 8000);
		assertThat(summary.get("missedFlashcardIds").get(0).asString()).isEqualTo(forgotten);
		assertThat(summary.get("missedExerciseIds").get(0).asString())
			.isEqualTo(items.get(1).get("exercise").get("id").asString());

		// Completing again changes nothing
		JsonNode again = json(mvc
			.perform(post("/api/practice-sessions/{id}/complete", id).header("Authorization", anna.bearer()))
			.andExpect(status().isOk()));
		assertThat(again.get("completedAt").asString()).isEqualTo(completed.get("completedAt").asString());
		// A finished session takes no more answers
		review(anna, remembered, "EASY", id).andExpect(status().isConflict());

		JsonNode list = json(mvc.perform(get("/api/practice-sessions").header("Authorization", anna.bearer()))
			.andExpect(status().isOk()));
		assertThat(list.get("content")).hasSize(1);
		assertThat(list.get("content").get(0).get("items").asInt()).isEqualTo(6);
		assertThat(list.get("content").get(0).get("answered").asInt()).isEqualTo(4);
		assertThat(list.get("content").get(0).get("status").asString()).isEqualTo("COMPLETED");

		// Practise the mistakes again: the forgotten card and the wrong exercise
		JsonNode retry = json(mvc
			.perform(post("/api/practice-sessions/{id}/retry", id).header("Authorization", anna.bearer()))
			.andExpect(status().isCreated()));
		assertThat(retry.get("aiJobId").isNull()).isTrue();
		JsonNode again2 = retry.get("practiceSession");
		assertThat(again2.get("id").asString()).isNotEqualTo(id);
		assertThat(again2.get("mode").asString()).isEqualTo("FULL_REVISION");
		assertThat(again2.get("exerciseSet").isNull()).isTrue();
		assertThat(pattern(again2.get("items"))).isEqualTo("FE");
		assertThat(again2.get("items").get(0).get("flashcard").get("card").get("id").asString()).isEqualTo(forgotten);
		assertThat(again2.get("items").get(1).get("exercise").get("id").asString())
			.isEqualTo(items.get(1).get("exercise").get("id").asString());
		// Its answers are tracked like any session's
		review(anna, forgotten, "GOOD", again2.get("id").asString()).andExpect(status().isCreated());
		// Only a completed session can be retried
		mvc.perform(post("/api/practice-sessions/{id}/retry", again2.get("id").asString())
			.header("Authorization", anna.bearer())).andExpect(status().isConflict());
		OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.POST, "/api/practice-sessions/{id}/retry",
				UUID.fromString(id), anna.accessToken(), ben.accessToken());
	}

	@Test
	void answersMustBelongToTheSession() throws Exception {
		JsonNode session = started(anna, """
				{"mode":"FULL_REVISION","length":5,"includeExercises":false}
				""").get("practiceSession");
		String id = session.get("id").asString();
		Set<String> shown = new HashSet<>();
		session.get("items").forEach(item -> shown.add(item.get("flashcard").get("card").get("id").asString()));
		String other = jdbc
			.queryForList("select id::text from flashcards where user_id = ?", String.class, anna.id())
			.stream()
			.filter(card -> !shown.contains(card))
			.findFirst()
			.orElseThrow();

		review(anna, other, "GOOD", id).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("practiceSessionId"));
		// Nothing was recorded
		assertThat(jdbc.queryForObject("select count(*) from flashcard_reviews", Integer.class)).isZero();

		UUID bensSession = UUID.randomUUID();
		jdbc.update("insert into practice_sessions (id, user_id, language_code, mode, config, started_at) "
				+ "values (?, ?, 'de', 'MIXED', '{}', now())", bensSession, ben.id());
		review(anna, shown.iterator().next(), "GOOD", bensSession.toString()).andExpect(status().isNotFound());
	}

	@Test
	void onlyTheOwnerSeesASession() throws Exception {
		UUID id = UUID.fromString(started(anna, """
				{"mode":"MIXED","length":5,"includeExercises":false}
				""").get("practiceSession").get("id").asString());
		OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.GET, "/api/practice-sessions/{id}", id,
				anna.accessToken(), ben.accessToken());
		OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.POST, "/api/practice-sessions/{id}/complete",
				id, anna.accessToken(), ben.accessToken());
		assertThat(json(mvc.perform(get("/api/practice-sessions").header("Authorization", ben.bearer())))
			.get("content")).isEmpty();
	}

	@Test
	void requestsAreChecked() throws Exception {
		start(anna, """
				{"mode":"MIXED","includeFlashcards":false,"includeExercises":false}
				""").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("includeFlashcards"));
		start(anna, """
				{"mode":"MIXED","studySessionId":"%s"}
				""".formatted(UUID.randomUUID())).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("studySessionId"));
		start(anna, """
				{"mode":"MIXED","length":3}
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("length"));
		start(anna, """
				{"mode":"MIXED","newRatio":1.5}
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("newRatio"));
		start(anna, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("mode"));
	}

	@Test
	void withoutMaterialThereIsNothingToPractise() throws Exception {
		start(ben, """
				{"mode":"MIXED"}
				""").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOTHING_TO_PRACTISE"));
		// Exercises only, but Anna has no topic yet
		start(anna, """
				{"mode":"MIXED","includeFlashcards":false}
				""").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOTHING_TO_PRACTISE"));
		start(anna, """
				{"mode":"CURRENT_LESSON"}
				""").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_STUDY_SESSION"));
	}

	@Test
	void theLengthDefaultsToTheLearnersSetting() throws Exception {
		jdbc.update("update user_languages set practice_session_length = 5 where user_id = ?", anna.id());
		JsonNode session = started(anna, """
				{"mode":"MIXED"}
				""").get("practiceSession");
		assertThat(session.get("config").get("length").asInt()).isEqualTo(5);
		// No topic, so no exercises: all five are cards
		assertThat(pattern(session.get("items"))).isEqualTo("FFFFF");
	}

	@Test
	void sessionsWithSeveralTopicsAskForCombinedExercises() throws Exception {
		scriptExercises();
		topic(anna, "Dativ");
		topic(anna, "Perfekt");
		String id = started(anna, """
				{"mode":"FULL_REVISION","length":6}
				""").get("practiceSession").get("id").asString();
		assertThat(jdbc.queryForObject(
				"select request ->> 'combined' from exercise_sets where practice_session_id = ?::uuid", String.class, id))
			.isEqualTo("true");
	}

	@Test
	void theOverviewCountsWhatEachModeWouldUse() throws Exception {
		topic(anna, "Dativ");
		JsonNode overview = json(mvc.perform(get("/api/practice-sessions/overview").header("Authorization",
				anna.bearer())).andExpect(status().isOk()));
		assertThat(overview.get("currentLesson").isNull()).isTrue();
		assertThat(overview.get("words").asInt()).isEqualTo(6);
		// Never practised, so all fresh
		assertThat(overview.get("freshWords").asInt()).isEqualTo(6);
		assertThat(overview.get("topics").asInt()).isEqualTo(1);
		assertThat(overview.get("practiceSessionLength").asInt()).isEqualTo(20);
		assertThat(overview.get("inProgress").isNull()).isTrue();

		UUID lesson = UUID.randomUUID();
		jdbc.update("insert into study_sessions (id, user_id, language_code, title, studied_on) "
				+ "values (?, ?, 'de', 'Lektion 4', '2026-11-10')", lesson, anna.id());
		jdbc.update("insert into session_vocabulary (study_session_id, vocabulary_item_id) values (?, ?)", lesson,
				words.get(0));
		String id = started(anna, """
				{"mode":"CURRENT_LESSON","includeExercises":false}
				""").get("practiceSession").get("id").asString();

		overview = json(mvc.perform(get("/api/practice-sessions/overview").header("Authorization", anna.bearer()))
			.andExpect(status().isOk()));
		JsonNode current = overview.get("currentLesson");
		assertThat(current.get("id").asString()).isEqualTo(lesson.toString());
		assertThat(current.get("title").asString()).isEqualTo("Lektion 4");
		assertThat(current.get("studiedOn").asString()).isEqualTo("2026-11-10");
		assertThat(current.get("words").asInt()).isEqualTo(1);
		assertThat(current.get("topics").asInt()).isZero();
		assertThat(overview.get("inProgress").get("id").asString()).isEqualTo(id);
		assertThat(overview.get("inProgress").get("items").asInt()).isEqualTo(1);
	}

}
