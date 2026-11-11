package com.linguaflow.flashcard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.testsupport.OwnershipAssertions;
import com.linguaflow.testsupport.TestUsers;
import com.linguaflow.testsupport.TestUsers.TestUser;
import com.linguaflow.user.UserRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Browsing and suspending flashcards (FC-02). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class FlashcardApiTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private FlashcardGenerator generator;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private UserRepository users;

	@Autowired
	private RegistrationService registration;

	@Autowired
	private AccessTokenService accessTokens;

	private TestUsers testUsers;

	private TestUser anna;

	private TestUser ben;

	private UUID hund;

	private UUID laufen;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		testUsers = new TestUsers(registration, accessTokens, jdbc);
		anna = testUsers.learner("anna@example.de");
		ben = testUsers.learner("ben@example.de");
		hund = word(anna, "Hund", "dog", "NOUN", "MASCULINE", "Hunde", "{}");
		laufen = word(anna, "laufen", "to run", "VERB", null, null,
				"{\"praeteritum\":\"lief\",\"partizip2\":\"gelaufen\",\"auxiliary\":\"SEIN\"}");
		jdbc.update("insert into vocabulary_examples (id, vocabulary_item_id, sentence, translation, position, "
				+ "ai_generated) values (?, ?, 'Der Hund bellt.', 'The dog barks.', 0, true)", UUID.randomUUID(), hund);
		generator.createFor(anna.id(), hund);
		generator.createFor(anna.id(), laufen);
	}

	private UUID word(TestUser user, String lemma, String translation, String partOfSpeech, String gender,
			String plural, String extra) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma, translation,
				    part_of_speech, gender, plural, extra, ai_status)
				values (?, ?, 'de', ?, ?, ?, ?, ?, ?, ?::jsonb, 'READY')""", id, user.id(), lemma,
				lemma.toLowerCase(), translation, partOfSpeech, gender, plural, extra);
		return id;
	}

	private UUID card(UUID itemId, String direction) {
		return jdbc.queryForObject("select id from flashcards where vocabulary_item_id = ? and direction = ?",
				UUID.class, itemId, direction);
	}

	private ResultActions send(TestUser user, MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header("Authorization", user.bearer()));
	}

	@Test
	void listsCardsWithContentAndReviewState() throws Exception {
		send(anna, get("/api/flashcards").param("vocabularyItemId", hund.toString()).param("direction",
				"TARGET_TO_NATIVE"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[0].lemma").value("Hund"))
			.andExpect(jsonPath("$.content[0].direction").value("TARGET_TO_NATIVE"))
			.andExpect(jsonPath("$.content[0].suspended").value(false))
			.andExpect(jsonPath("$.content[0].content.front").value("der Hund"))
			.andExpect(jsonPath("$.content[0].content.back").value("dog"))
			.andExpect(jsonPath("$.content[0].content.plural").value("die Hunde"))
			.andExpect(jsonPath("$.content[0].content.examples[0].sentence").value("Der Hund bellt."))
			.andExpect(jsonPath("$.content[0].content.complete").value(true))
			.andExpect(jsonPath("$.content[0].srs.state").value("NEW"))
			.andExpect(jsonPath("$.content[0].srs.dueAt").doesNotExist());

		send(anna, get("/api/flashcards").param("vocabularyItemId", laufen.toString()).param("direction",
				"NATIVE_TO_TARGET"))
			.andExpect(jsonPath("$.content[0].content.front").value("to run"))
			.andExpect(jsonPath("$.content[0].content.hint").value("verb"))
			.andExpect(jsonPath("$.content[0].content.back").value("laufen"))
			.andExpect(jsonPath("$.content[0].content.praeteritum").value("lief"))
			.andExpect(jsonPath("$.content[0].content.perfect").value("ist gelaufen"));

		send(anna, get("/api/flashcards")).andExpect(jsonPath("$.totalElements").value(4))
			.andExpect(jsonPath("$.size").value(20));
		// Ben has no words, so no cards: nothing of Anna's leaks
		send(ben, get("/api/flashcards")).andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void filtersBySearchDirectionStateAndSuspension() throws Exception {
		// Search covers the word (normalized) and its translation
		send(anna, get("/api/flashcards").param("q", "HUND")).andExpect(jsonPath("$.totalElements").value(2));
		send(anna, get("/api/flashcards").param("q", "run")).andExpect(jsonPath("$.totalElements").value(2));
		send(anna, get("/api/flashcards").param("q", "%")).andExpect(jsonPath("$.totalElements").value(0));
		send(anna, get("/api/flashcards").param("direction", "NATIVE_TO_TARGET"))
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.content[*].direction", everyItem(is("NATIVE_TO_TARGET"))));

		UUID reviewed = card(laufen, "TARGET_TO_NATIVE");
		jdbc.update("update srs_state set state = 'REVIEW', due_at = now() + interval '2 days', interval_days = 2 "
				+ "where flashcard_id = ?", reviewed);
		send(anna, get("/api/flashcards").param("state", "REVIEW"))
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[0].id").value(reviewed.toString()))
			.andExpect(jsonPath("$.content[0].srs.intervalDays").value(2));
		// Due first; NEW cards have no due date and come last
		send(anna, get("/api/flashcards").param("sort", "dueAt,asc"))
			.andExpect(jsonPath("$.content[0].id").value(reviewed.toString()));

		send(anna, patch("/api/flashcards/{id}", reviewed).contentType(MediaType.APPLICATION_JSON)
			.content("{\"suspended\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.suspended").value(true));
		send(anna, get("/api/flashcards").param("suspended", "true"))
			.andExpect(jsonPath("$.totalElements").value(1));
		send(anna, get("/api/flashcards").param("suspended", "false"))
			.andExpect(jsonPath("$.totalElements").value(3));

		send(anna, get("/api/flashcards").param("sort", "front,asc")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("sort"));
	}

	@Test
	void suspendingIsReversibleAndKeepsTheReviewState() throws Exception {
		UUID id = card(hund, "NATIVE_TO_TARGET");
		jdbc.update("update srs_state set repetitions = 3 where flashcard_id = ?", id);

		send(anna, patch("/api/flashcards/{id}", id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"suspended\":true}")).andExpect(jsonPath("$.suspended").value(true));
		send(anna, patch("/api/flashcards/{id}", id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"suspended\":false}"))
			.andExpect(jsonPath("$.suspended").value(false))
			.andExpect(jsonPath("$.srs.repetitions").value(3));

		send(anna, patch("/api/flashcards/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("suspended"));
	}

	@Test
	void showsTheWordAsItIsNow() throws Exception {
		UUID id = card(hund, "TARGET_TO_NATIVE");
		send(anna, get("/api/flashcards/{id}", id)).andExpect(jsonPath("$.content.back").value("dog"));

		jdbc.update("update vocabulary_items set translation = 'hound' where id = ?", hund);

		send(anna, get("/api/flashcards/{id}", id)).andExpect(jsonPath("$.content.back").value("hound"));
	}

	@Test
	void onlyTheOwnerSeesOrChangesACard() throws Exception {
		UUID id = card(hund, "TARGET_TO_NATIVE");
		OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.GET, "/api/flashcards/{id}", id,
				anna.accessToken(), ben.accessToken());
		OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.PATCH, "/api/flashcards/{id}", id,
				anna.accessToken(), ben.accessToken(), "{\"suspended\":true}");
		assertThat(jdbc.queryForObject("select suspended from flashcards where id = ?", Boolean.class, id)).isTrue();
	}

	@Test
	void listingNeedsAnActiveLanguage() throws Exception {
		TestUser newcomer = testUsers.user("new@example.de");
		send(newcomer, get("/api/flashcards")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NO_ACTIVE_LANGUAGE"));
	}

}
