package com.linguaflow.ai;

import java.util.UUID;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.ai.job.AiJobWorker;
import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.testsupport.TestData;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The scripted provider (QA-05) drives every AI feature through the real API, queue and
 * output checks, as the end-to-end tests rely on: words get enriched (and so get
 * flashcards), exercise sets fill up and explanations are written.
 */
@SpringBootTest(properties = "linguaflow.ai.provider=scripted")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ScriptedProviderTests {

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
	private AiJobWorker worker;

	@Autowired
	private LlmClient client;

	private TestUser anna;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		jdbc.update("delete from ai_jobs");
		anna = new TestUsers(registration, accessTokens, jdbc).learner("anna@example.de");
	}

	private void send(String uri, String body, int expectedStatus) throws Exception {
		mvc.perform(post(uri).header("Authorization", anna.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).andExpect(status().is(expectedStatus));
	}

	@Test
	void isTheClientWhenChosen() {
		assertThat(client).isInstanceOf(ScriptedLlmClient.class);
		assertThat(client.provider()).isEqualTo("scripted");
	}

	@Test
	void wordsAreEnrichedAndGetFlashcards() throws Exception {
		send("/api/vocabulary/bulk/confirm", """
				{"items": [{"lemma": "Hund"}, {"lemma": "laufen", "partOfSpeech": "VERB"}, {"lemma": "Quatsch"},
				 {"lemma": "blinzeln"}], "tagIds": []}""", 200);
		send("/api/vocabulary", "{\"lemma\": \"schön\"}", 201);

		worker.runDueJobs();

		assertThat(jdbc.queryForList("select ai_status from vocabulary_items where user_id = ?", String.class,
				anna.id()))
			.hasSize(5)
			.containsOnly("READY");
		assertThat(jdbc.queryForObject("select gender || ' ' || plural || ' ' || translation from vocabulary_items "
				+ "where user_id = ? and lemma = 'Hund'", String.class, anna.id()))
			.isEqualTo("MASCULINE Hunde dog");
		assertThat(jdbc.queryForObject("select extra->>'partizip2' = 'gelaufen' from vocabulary_items "
				+ "where user_id = ? and lemma = 'laufen'", Boolean.class, anna.id()))
			.isTrue();
		// Unknown words get a placeholder that says so
		assertThat(jdbc.queryForObject("select translation from vocabulary_items where user_id = ? and lemma = "
				+ "'Quatsch'", String.class, anna.id()))
			.isEqualTo("[Quatsch]");
		assertThat(jdbc.queryForObject("select count(*) from flashcards where user_id = ?", Integer.class,
				anna.id()))
			.isEqualTo(10);
	}

	@Test
	void exerciseSetsFillUpAndExplanationsAreWritten() throws Exception {
		UUID dativ = new TestData(jdbc).topic(anna.id(), "Dativ").status("LEARNING").insert();
		send("/api/exercise-sets", """
				{"grammarTopicIds": ["%s"], "types": ["FILL_BLANK", "MULTIPLE_CHOICE", "WORD_ORDER"], "count": 10}"""
			.formatted(dativ), 202);
		send("/api/grammar-topics/" + dativ + "/explanation", "{}", 202);

		worker.runDueJobs();

		assertThat(jdbc.queryForObject("select status from exercise_sets where user_id = ?", String.class, anna.id()))
			.isEqualTo("READY");
		assertThat(jdbc.queryForObject("select count(*) from exercises where user_id = ?", Integer.class, anna.id()))
			.isEqualTo(10);
		assertThat(jdbc.queryForObject("select count(*) from exercise_grammar_topics g join exercises e "
				+ "on e.id = g.exercise_id where e.user_id = ? and g.grammar_topic_id = ?", Integer.class, anna.id(),
				dativ))
			.isEqualTo(10);
		assertThat(jdbc.queryForObject("select ai_explanation->>'summary' from grammar_topics where id = ?",
				String.class, dativ))
			.contains("Dativ");
	}

}
