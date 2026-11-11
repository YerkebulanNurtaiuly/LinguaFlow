package com.linguaflow.flashcard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.testsupport.TestUsers;
import com.linguaflow.testsupport.TestUsers.TestUser;
import com.linguaflow.user.UserRepository;
import com.linguaflow.vocabulary.VocabularyItemRepository;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Flashcards appear for READY words (FC-01), from enrichment and from the startup catch-up. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class FlashcardGenerationTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Pattern WORDS = Pattern.compile("<words>\\n(.*?)\\n</words>", Pattern.DOTALL);

	@Autowired
	private MockMvc mvc;

	@Autowired
	private LlmClient client;

	@Autowired
	private AiJobWorker worker;

	@Autowired
	private FlashcardGenerator generator;

	@Autowired
	private VocabularyItemRepository words;

	@Autowired
	private TransactionTemplate tx;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private UserRepository users;

	@Autowired
	private RegistrationService registration;

	@Autowired
	private AccessTokenService accessTokens;

	private TestUser anna;

	private FakeLlmClient fake() {
		return (FakeLlmClient) client;
	}

	@BeforeEach
	void setUp() {
		users.deleteAll();
		jdbc.update("delete from ai_jobs");
		jdbc.update("delete from ai_cache");
		fake().reset();
		// Every word is a neuter noun with two examples
		fake().respondTo(AiUseCase.VOCABULARY_ENRICHMENT, prompt -> {
			Matcher batch = WORDS.matcher(prompt.user());
			batch.find();
			return JSON.writeValueAsString(Map.of("words", batch.group(1).lines().map(line -> {
				String word = line.replaceFirst("^- ", "");
				return Map.of("word", word, "partOfSpeech", "NOUN", "gender", "NEUTER", "plural", word + "e",
						"translation", "t-" + word, "cefrLevel", "A1", "examples",
						List.of(Map.of("sentence", "Das " + word + ".", "translation", "The " + word + "."),
								Map.of("sentence", "Ein " + word + ".", "translation", "A " + word + ".")));
			}).toList()));
		});
		anna = new TestUsers(registration, accessTokens, jdbc).learner("anna@example.de");
	}

	@AfterEach
	void tearDown() {
		fake().reset();
	}

	private UUID add(String lemma) throws Exception {
		String body = mvc
			.perform(post("/api/vocabulary").header("Authorization", anna.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"lemma\":\"" + lemma + "\"}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JSON.readTree(body).get("id").asString());
	}

	private List<String> directions(UUID itemId) {
		return jdbc.queryForList("select direction from flashcards where vocabulary_item_id = ? order by direction",
				String.class, itemId);
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	@Test
	void aWordGetsBothCardsWhenTheAiHasFilledItIn() throws Exception {
		UUID id = add("Haus");
		assertThat(directions(id)).as("no cards while the word waits for AI").isEmpty();

		worker.runDueJobs();

		assertThat(directions(id)).containsExactly("NATIVE_TO_TARGET", "TARGET_TO_NATIVE");
		assertThat(count("select count(*) from srs_state s join flashcards f on f.id = s.flashcard_id "
				+ "where f.vocabulary_item_id = ? and s.state = 'NEW' and s.due_at is null and s.ease_factor = 2.50",
				id)).isEqualTo(2);
		assertThat(count("select count(*) from flashcards where vocabulary_item_id = ? and user_id = ?", id,
				anna.id())).isEqualTo(2);

		// The content comes from the word, as it is now
		FlashcardContent content = tx.execute(status -> FlashcardContent.of(CardDirection.NATIVE_TO_TARGET,
				words.requireOwned(id, anna.id())));
		assertThat(content.front()).isEqualTo("t-Haus");
		assertThat(content.back()).isEqualTo("das Haus");
		assertThat(content.plural()).isEqualTo("die Hause");
		assertThat(content.complete()).isTrue();
	}

	@Test
	void creatingIsIdempotentAndDeletingTheWordRemovesItsCards() throws Exception {
		UUID id = add("Buch");
		worker.runDueJobs();

		assertThat(generator.createFor(anna.id(), id)).isEmpty();
		assertThat(directions(id)).hasSize(2);

		mvc.perform(delete("/api/vocabulary/{id}", id).header("Authorization", anna.bearer()))
			.andExpect(status().isNoContent());
		assertThat(count("select count(*) from flashcards")).isZero();
		assertThat(count("select count(*) from srs_state")).isZero();
	}

	@Test
	void theStartupCatchUpCreatesMissingCardsForReadyWordsOnly() {
		UUID ready = FlashcardRepositoryTests.insertWord(jdbc, anna.id(), "tisch");
		UUID halfDone = FlashcardRepositoryTests.insertWord(jdbc, anna.id(), "stuhl");
		UUID pending = FlashcardRepositoryTests.insertWord(jdbc, anna.id(), "lampe");
		jdbc.update("update vocabulary_items set ai_status = 'READY', translation = 'x' where id in (?, ?)", ready,
				halfDone);
		generator.createFor(anna.id(), halfDone);
		jdbc.update("delete from flashcards where vocabulary_item_id = ? and direction = 'NATIVE_TO_TARGET'", halfDone);

		assertThat(generator.reconcile()).isEqualTo(3);

		assertThat(directions(ready)).hasSize(2);
		assertThat(directions(halfDone)).hasSize(2);
		assertThat(directions(pending)).isEmpty();
		assertThat(generator.reconcile()).isZero();
	}

	@Test
	void learnersChooseWhichDirectionsTheyReview() throws Exception {
		mvc.perform(get("/api/users/me/languages").header("Authorization", anna.bearer()))
			.andExpect(jsonPath("$[0].settings.cardDirections.length()").value(2));

		mvc.perform(patch("/api/users/me/languages/de").header("Authorization", anna.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"settings\":{\"cardDirections\":[\"TARGET_TO_NATIVE\",\"TARGET_TO_NATIVE\"]}}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.settings.cardDirections.length()").value(1))
			.andExpect(jsonPath("$.settings.cardDirections[0]").value("TARGET_TO_NATIVE"));

		mvc.perform(patch("/api/users/me/languages/de").header("Authorization", anna.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"settings\":{\"cardDirections\":[]}}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("settings.cardDirections"));

		// Cards still exist for both directions; the setting only picks what's reviewed
		UUID id = add("Kind");
		worker.runDueJobs();
		assertThat(directions(id)).hasSize(2);
	}

}
