package com.linguaflow.vocabulary;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.testsupport.OwnershipAssertions;
import com.linguaflow.testsupport.TestUsers;
import com.linguaflow.testsupport.TestUsers.TestUser;
import com.linguaflow.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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

/** The vocabulary HTTP API end to end (VOC-02). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class VocabularyApiTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private RegistrationService registration;

	@Autowired
	private AccessTokenService accessTokens;

	@Autowired
	private JdbcTemplate jdbc;

	private TestUser anna;

	private TestUser ben;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		TestUsers testUsers = new TestUsers(registration, accessTokens, jdbc);
		anna = testUsers.learner("anna@example.de");
		ben = testUsers.learner("ben@example.de");
	}

	@AfterEach
	void tearDown() {
		users.deleteAll();
	}

	private MockHttpServletRequestBuilder as(TestUser user, MockHttpServletRequestBuilder request) {
		return request.header(AUTHORIZATION, user.bearer());
	}

	private ResultActions add(TestUser user, String json) throws Exception {
		return mvc.perform(as(user, post("/api/vocabulary")).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private UUID addWord(TestUser user, String json) throws Exception {
		String body = add(user, json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	private UUID addWord(TestUser user, String lemma, String extraJson) throws Exception {
		return addWord(user, "{\"lemma\":\"" + lemma + "\"" + (extraJson.isEmpty() ? "" : "," + extraJson) + "}");
	}

	private ResultActions list(TestUser user, String query) throws Exception {
		return mvc.perform(as(user, get("/api/vocabulary" + query)));
	}

	@Nested
	class Create {

		@Test
		void addsAWordAndReturnsIt() throws Exception {
			add(anna, """
					{"lemma":"der Hund","partOfSpeech":"NOUN","translation":"dog","gender":"MASCULINE",
					 "plural":"die Hunde","cefrLevel":"A1",
					 "examples":[{"sentence":"Der Hund bellt.","translation":"The dog barks."}]}
					""")
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", containsString("/api/vocabulary/")))
				.andExpect(jsonPath("$.lemma").value("der Hund"))
				.andExpect(jsonPath("$.article").value("der"))
				.andExpect(jsonPath("$.translation").value("dog"))
				.andExpect(jsonPath("$.status").value("LEARNING"))
				.andExpect(jsonPath("$.aiStatus").value("PENDING_AI"))
				.andExpect(jsonPath("$.examples[0].sentence").value("Der Hund bellt."))
				.andExpect(jsonPath("$.examples[0].aiGenerated").value(false))
				.andExpect(jsonPath("$.tags").isEmpty())
				.andExpect(jsonPath("$.version").value(0));
		}

		/** VOC-02 "Done when": duplicates return 409 with the existing item's ID. */
		@Test
		void rejectsADuplicateWithTheExistingId() throws Exception {
			UUID existing = addWord(anna, "der Hund", "");

			add(anna, "{\"lemma\":\"Hund\"}").andExpect(status().isConflict())
				.andExpect(header().string("Content-Type", containsString("application/problem+json")))
				.andExpect(jsonPath("$.code").value("VOCABULARY_DUPLICATE"))
				.andExpect(jsonPath("$.existingId").value(existing.toString()))
				.andExpect(jsonPath("$.detail").value("\"Hund\" is already in your vocabulary"));
		}

		@Test
		void aWordWithoutTypeIsADuplicateOfTheTypedWord() throws Exception {
			UUID dog = addWord(anna, "der Hund", "\"partOfSpeech\":\"NOUN\"");

			add(anna, "{\"lemma\":\"Hund\"}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.existingId").value(dog.toString()));
			// Another part of speech is another word
			add(anna, "{\"lemma\":\"hund\",\"partOfSpeech\":\"OTHER\"}").andExpect(status().isCreated());
		}

		@Test
		void validatesTheBody() throws Exception {
			add(anna, "{\"lemma\":\"  \"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("lemma"));
			add(anna, "{\"lemma\":\"Hund\",\"status\":\"MASTERED\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("status"));
			add(anna, "{\"lemma\":\"Hund\",\"examples\":[{\"sentence\":\"\"}]}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("examples[0].sentence"));
			add(anna, "{\"lemma\":\"Hund\",\"partOfSpeech\":\"NOUNISH\"}").andExpect(status().isBadRequest());
		}

		@Test
		void needsALanguageFirst() throws Exception {
			TestUser newcomer = new TestUsers(registration, accessTokens, jdbc).user("new@example.de");

			add(newcomer, "{\"lemma\":\"Hund\"}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NO_ACTIVE_LANGUAGE"));
		}

		@Test
		void requiresAuthentication() throws Exception {
			mvc.perform(post("/api/vocabulary").contentType(MediaType.APPLICATION_JSON).content("{\"lemma\":\"x\"}"))
				.andExpect(status().isUnauthorized());
		}

	}

	@Nested
	class ListAndSearch {

		@Test
		void listsNewestFirstWithPageMetadata() throws Exception {
			addWord(anna, "der Hund", "");
			addWord(anna, "die Katze", "");
			addWord(anna, "das Pferd", "");
			addWord(ben, "die Maus", "");

			list(anna, "?size=2").andExpect(status().isOk())
				.andExpect(jsonPath("$.content[*].lemma", contains("das Pferd", "die Katze")))
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.totalPages").value(2))
				.andExpect(jsonPath("$.sort[0].property").value("createdAt"))
				.andExpect(jsonPath("$.sort[0].direction").value("DESC"));
			list(anna, "?size=2&page=1").andExpect(jsonPath("$.content[*].lemma", contains("der Hund")));
		}

		@Test
		void sortsByLemmaIgnoringTheArticle() throws Exception {
			addWord(anna, "das Zebra", "");
			addWord(anna, "der Apfel", "");
			addWord(anna, "die Banane", "");

			list(anna, "?sort=lemma,asc").andExpect(
					jsonPath("$.content[*].lemma", contains("der Apfel", "die Banane", "das Zebra")))
				.andExpect(jsonPath("$.sort[0].property").value("lemma"));
		}

		@Test
		void rejectsUnknownSortProperties() throws Exception {
			list(anna, "?sort=userId").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("sort"))
				.andExpect(jsonPath("$.errors[0].message", containsString("can't sort by 'userId'")));
		}

		@Test
		void searchesLemmaAndTranslation() throws Exception {
			addWord(anna, "die Straße", "\"translation\":\"street\"");
			addWord(anna, "der Hund", "\"translation\":\"dog\"");
			addWord(anna, "der Hot Dog", "\"translation\":\"hot dog\"");

			list(anna, "?q=STRASSE").andExpect(jsonPath("$.content[*].lemma", contains("die Straße")));
			list(anna, "?q=der hu").andExpect(jsonPath("$.content[*].lemma", contains("der Hund")));
			list(anna, "?q=Dog&sort=lemma").andExpect(jsonPath("$.content[*].lemma", contains("der Hot Dog", "der Hund")));
		}

		@Test
		void treatsLikeWildcardsInTheSearchLiterally() throws Exception {
			addWord(anna, "der Hund", "\"translation\":\"dog\"");
			addWord(anna, "100% sicher", "\"translation\":\"absolutely sure\"");

			mvc.perform(as(anna, get("/api/vocabulary").param("q", "%")))
				.andExpect(jsonPath("$.content[*].lemma", contains("100% sicher")));
			mvc.perform(as(anna, get("/api/vocabulary").param("q", "_")))
				.andExpect(jsonPath("$.content", hasSize(0)));
		}

		@Test
		void filtersByPartOfSpeechLevelAndStatus() throws Exception {
			addWord(anna, "der Hund", "\"partOfSpeech\":\"NOUN\",\"cefrLevel\":\"A1\",\"status\":\"NEW\"");
			addWord(anna, "laufen", "\"partOfSpeech\":\"VERB\",\"cefrLevel\":\"A1\"");
			addWord(anna, "schnell", "\"partOfSpeech\":\"ADJECTIVE\",\"cefrLevel\":\"A2\",\"status\":\"LEARNED\"");

			list(anna, "?partOfSpeech=NOUN&partOfSpeech=VERB&sort=lemma")
				.andExpect(jsonPath("$.content[*].lemma", contains("der Hund", "laufen")));
			list(anna, "?cefrLevel=A2").andExpect(jsonPath("$.content[*].lemma", contains("schnell")));
			list(anna, "?status=NEW&status=LEARNED&cefrLevel=A1")
				.andExpect(jsonPath("$.content[*].lemma", contains("der Hund")));
			list(anna, "?status=WHATEVER").andExpect(status().isBadRequest());
		}

		@Test
		void filtersByAiStatus() throws Exception {
			UUID ready = addWord(anna, "der Hund", "");
			addWord(anna, "die Katze", "");
			jdbc.update("update vocabulary_items set ai_status = 'READY' where id = ?", ready);

			list(anna, "?aiStatus=PENDING_AI").andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].lemma").value("die Katze"));
			list(anna, "?aiStatus=READY&aiStatus=AI_FAILED").andExpect(jsonPath("$.totalElements").value(1));
		}

		@Test
		void filtersByTagAndStudySession() throws Exception {
			UUID dog = addWord(anna, "der Hund", "");
			UUID cat = addWord(anna, "die Katze", "");
			addWord(anna, "das Brot", "");
			UUID animals = UUID.randomUUID();
			jdbc.update("insert into tags (id, user_id, language_code, name) values (?, ?, 'de', 'Tiere')", animals,
					anna.id());
			jdbc.update("insert into vocabulary_item_tags (vocabulary_item_id, tag_id) values (?, ?), (?, ?)", dog,
					animals, cat, animals);
			UUID lesson = UUID.randomUUID();
			jdbc.update("insert into study_sessions (id, user_id, language_code, title, studied_on) "
					+ "values (?, ?, 'de', 'Lektion 1', current_date)", lesson, anna.id());
			jdbc.update("insert into session_vocabulary (study_session_id, vocabulary_item_id) values (?, ?)", lesson,
					dog);

			list(anna, "?tagId=" + animals + "&sort=lemma")
				.andExpect(jsonPath("$.content[*].lemma", contains("der Hund", "die Katze")))
				.andExpect(jsonPath("$.content[0].tags[0].name").value("Tiere"));
			list(anna, "?sessionId=" + lesson).andExpect(jsonPath("$.content[*].lemma", contains("der Hund")));
			list(anna, "?sessionId=" + lesson + "&tagId=" + animals).andExpect(jsonPath("$.totalElements").value(1));
			// Someone else's session or tag simply matches none of your words
			list(ben, "?sessionId=" + lesson).andExpect(jsonPath("$.totalElements").value(0));
		}

		@Test
		void capsTheSearchLength() throws Exception {
			list(anna, "?q=" + "x".repeat(201)).andExpect(status().isBadRequest());
		}

	}

	@Nested
	class ReadUpdateDelete {

		@Test
		void getsUpdatesAndDeletesAWord() throws Exception {
			UUID id = addWord(anna, "der Hund", "\"translation\":\"hound\",\"notes\":\"bellt\"");

			mvc.perform(as(anna, get("/api/vocabulary/{id}", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.translation").value("hound"));

			mvc.perform(as(anna, patch("/api/vocabulary/{id}", id)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"translation\":\"dog\",\"notes\":\"\",\"cefrLevel\":\"A1\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.translation").value("dog"))
				.andExpect(jsonPath("$.notes").value(nullValue()))
				.andExpect(jsonPath("$.cefrLevel").value("A1"))
				.andExpect(jsonPath("$.lemma").value("der Hund"))
				.andExpect(jsonPath("$.version").value(1));

			mvc.perform(as(anna, delete("/api/vocabulary/{id}", id))).andExpect(status().isNoContent());
			mvc.perform(as(anna, get("/api/vocabulary/{id}", id))).andExpect(status().isNotFound());
		}

		@Test
		void rejectsARenameOntoAnotherWord() throws Exception {
			UUID dog = addWord(anna, "der Hund", "");
			UUID cat = addWord(anna, "die Katze", "");

			mvc.perform(as(anna, patch("/api/vocabulary/{id}", cat)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"lemma\":\"hund\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("VOCABULARY_DUPLICATE"))
				.andExpect(jsonPath("$.existingId").value(dog.toString()));
		}

		@Test
		void rejectsABlankLemmaInAnUpdate() throws Exception {
			UUID id = addWord(anna, "der Hund", "");

			mvc.perform(as(anna, patch("/api/vocabulary/{id}", id)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"lemma\":\"   \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("lemma"));
		}

		@Test
		void onlyTheOwnerCanReadChangeOrDeleteAWord() throws Exception {
			UUID id = addWord(anna, "der Hund", "");

			OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.GET, "/api/vocabulary/{id}", id,
					anna.accessToken(), ben.accessToken());
			OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.PATCH, "/api/vocabulary/{id}", id,
					anna.accessToken(), ben.accessToken(), "{\"translation\":\"dog\"}");
			OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.DELETE, "/api/vocabulary/{id}", id,
					anna.accessToken(), ben.accessToken());
		}

	}

}
