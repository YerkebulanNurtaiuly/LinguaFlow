package com.linguaflow.learning;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;
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

/** Study sessions over HTTP (SES-01). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class StudySessionApiTests {

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

	private TestUsers testUsers;

	private TestUser anna;

	private TestUser ben;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		testUsers = new TestUsers(registration, accessTokens, jdbc);
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

	private ResultActions send(TestUser user, MockHttpServletRequestBuilder request, String json) throws Exception {
		return mvc.perform(as(user, request).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private UUID created(ResultActions result) throws Exception {
		return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id"));
	}

	private UUID session(TestUser user, String json) throws Exception {
		return created(send(user, post("/api/study-sessions"), json).andExpect(status().isCreated()));
	}

	private UUID word(TestUser user, String lemma) throws Exception {
		return created(send(user, post("/api/vocabulary"), "{\"lemma\":\"" + lemma + "\"}")
			.andExpect(status().isCreated()));
	}

	private UUID topic(TestUser user, String title) throws Exception {
		return created(send(user, post("/api/grammar-topics"), "{\"title\":\"" + title + "\"}")
			.andExpect(status().isCreated()));
	}

	private ResultActions attachWords(TestUser user, UUID sessionId, UUID... ids) throws Exception {
		StringBuilder json = new StringBuilder("{\"vocabularyItemIds\":[");
		for (int i = 0; i < ids.length; i++) {
			json.append(i == 0 ? "" : ",").append('"').append(ids[i]).append('"');
		}
		return send(user, post("/api/study-sessions/{id}/vocabulary", sessionId), json.append("]}").toString());
	}

	@Nested
	class Sessions {

		@Test
		void createsASessionForTodayByDefault() throws Exception {
			send(anna, post("/api/study-sessions"), "{\"title\":\"  Lesson   5 \",\"notes\":\"Dativ\"}")
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", containsString("/api/study-sessions/")))
				.andExpect(jsonPath("$.title").value("Lesson 5"))
				.andExpect(jsonPath("$.studiedOn").value(LocalDate.now(ZoneOffset.UTC).toString()))
				.andExpect(jsonPath("$.notes").value("Dativ"))
				.andExpect(jsonPath("$.active").value(false))
				.andExpect(jsonPath("$.vocabularyCount").value(0));
		}

		@Test
		void listsTheMostRecentLessonFirst() throws Exception {
			session(anna, "{\"title\":\"Lesson 1\",\"studiedOn\":\"2026-10-01\"}");
			session(anna, "{\"title\":\"Lesson 3\",\"studiedOn\":\"2026-10-15\"}");
			session(anna, "{\"title\":\"Lesson 2\",\"studiedOn\":\"2026-10-08\"}");
			session(ben, "{\"title\":\"Ben's\"}");

			mvc.perform(as(anna, get("/api/study-sessions")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.content[*].title", contains("Lesson 3", "Lesson 2", "Lesson 1")));
			mvc.perform(as(anna, get("/api/study-sessions?sort=title"))).andExpect(
					jsonPath("$.content[*].title", contains("Lesson 1", "Lesson 2", "Lesson 3")));
			mvc.perform(as(anna, get("/api/study-sessions?sort=notes"))).andExpect(status().isBadRequest());
		}

		@Test
		void editsAndDeletesASession() throws Exception {
			UUID id = session(anna, "{\"title\":\"Lesson 5\",\"notes\":\"old\"}");

			send(anna, patch("/api/study-sessions/{id}", id), "{\"studiedOn\":\"2026-11-01\",\"notes\":\"\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Lesson 5"))
				.andExpect(jsonPath("$.studiedOn").value("2026-11-01"))
				.andExpect(jsonPath("$.notes").value(nullValue()));

			mvc.perform(as(anna, delete("/api/study-sessions/{id}", id))).andExpect(status().isNoContent());
			mvc.perform(as(anna, get("/api/study-sessions/{id}", id))).andExpect(status().isNotFound());
		}

		@Test
		void validatesTheBody() throws Exception {
			send(anna, post("/api/study-sessions"), "{\"title\":\" \"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("title"));
			send(anna, post("/api/study-sessions"), "{\"title\":\"x\",\"studiedOn\":\"yesterday\"}")
				.andExpect(status().isBadRequest());
		}

		@Test
		void needsALanguageFirst() throws Exception {
			TestUser newcomer = testUsers.user("new@example.de");
			send(newcomer, post("/api/study-sessions"), "{\"title\":\"Lesson 1\"}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NO_ACTIVE_LANGUAGE"));
		}

		@Test
		void onlyTheOwnerCanUseASession() throws Exception {
			UUID id = session(anna, "{\"title\":\"Lesson 5\"}");
			String a = anna.accessToken();
			String b = ben.accessToken();
			OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.GET, "/api/study-sessions/{id}", id, a, b);
			OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.GET, "/api/study-sessions/{id}/material",
					id, a, b);
			OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.PATCH, "/api/study-sessions/{id}", id, a,
					b, "{\"notes\":\"mine\"}");
			OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.DELETE, "/api/study-sessions/{id}", id, a,
					b);
		}

	}

	@Nested
	class ActiveSession {

		@Test
		void onlyOneSessionIsActiveAtATime() throws Exception {
			mvc.perform(as(anna, get("/api/study-sessions/active"))).andExpect(status().isNoContent());
			UUID first = session(anna, "{\"title\":\"Lesson 4\",\"active\":true}");
			UUID second = session(anna, "{\"title\":\"Lesson 5\",\"active\":true}");

			mvc.perform(as(anna, get("/api/study-sessions/active"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(second.toString()));
			mvc.perform(as(anna, get("/api/study-sessions/{id}", first))).andExpect(jsonPath("$.active").value(false));

			send(anna, patch("/api/study-sessions/{id}", first), "{\"active\":true,\"title\":\"Lesson 4b\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(true))
				.andExpect(jsonPath("$.title").value("Lesson 4b"));
			mvc.perform(as(anna, get("/api/study-sessions/{id}", second))).andExpect(jsonPath("$.active").value(false));

			send(anna, patch("/api/study-sessions/{id}", first), "{\"active\":false}").andExpect(status().isOk());
			mvc.perform(as(anna, get("/api/study-sessions/active"))).andExpect(status().isNoContent());
		}

		@Test
		void newMaterialJoinsTheActiveSession() throws Exception {
			UUID before = word(anna, "der Apfel");
			UUID lesson = session(anna, "{\"title\":\"Lesson 5\",\"active\":true}");

			UUID dog = word(anna, "der Hund");
			UUID dative = topic(anna, "Dativ");
			send(anna, post("/api/vocabulary/bulk/confirm"), "{\"items\":[{\"lemma\":\"die Katze\"}]}")
				.andExpect(jsonPath("$.createdCount").value(1));
			word(ben, "Bens Wort");

			mvc.perform(as(anna, get("/api/study-sessions/{id}", lesson)))
				.andExpect(jsonPath("$.vocabularyCount").value(2))
				.andExpect(jsonPath("$.grammarTopicCount").value(1));
			mvc.perform(as(anna, get("/api/study-sessions/{id}/material", lesson)))
				.andExpect(jsonPath("$.vocabularyItemIds[0]").value(dog.toString()))
				.andExpect(jsonPath("$.grammarTopicIds", contains(dative.toString())));
			mvc.perform(as(anna, get("/api/vocabulary?sessionId=" + lesson)))
				.andExpect(jsonPath("$.content[*].lemma", containsInAnyOrder("der Hund", "die Katze")));
			// Words added before the session started aren't in it
			mvc.perform(as(anna, get("/api/study-sessions/{id}/material", lesson)))
				.andExpect(jsonPath("$.vocabularyItemIds", org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.hasItem(before.toString()))));
		}

		@Test
		void withoutAnActiveSessionNothingIsAttached() throws Exception {
			UUID lesson = session(anna, "{\"title\":\"Lesson 5\"}");
			word(anna, "der Hund");
			mvc.perform(as(anna, get("/api/study-sessions/{id}", lesson)))
				.andExpect(jsonPath("$.vocabularyCount").value(0));
		}

	}

	@Nested
	class Material {

		@Test
		void attachesAndDetachesWordsAndTopics() throws Exception {
			UUID lesson = session(anna, "{\"title\":\"Lesson 5\"}");
			UUID dog = word(anna, "der Hund");
			UUID cat = word(anna, "die Katze");
			UUID dative = topic(anna, "Dativ");

			attachWords(anna, lesson, dog, cat, dog).andExpect(status().isOk())
				.andExpect(jsonPath("$.vocabularyCount").value(2));
			// Attaching again is harmless
			attachWords(anna, lesson, dog).andExpect(jsonPath("$.vocabularyCount").value(2));
			send(anna, post("/api/study-sessions/{id}/grammar-topics", lesson),
					"{\"grammarTopicIds\":[\"" + dative + "\"]}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.grammarTopicCount").value(1));

			// "Current Lesson" practice reads the material by session
			mvc.perform(as(anna, get("/api/study-sessions/{id}/material", lesson)))
				.andExpect(jsonPath("$.vocabularyItemIds", contains(dog.toString(), cat.toString())))
				.andExpect(jsonPath("$.grammarTopicIds", contains(dative.toString())));
			mvc.perform(as(anna, get("/api/grammar-topics?sessionId=" + lesson)))
				.andExpect(jsonPath("$.content[*].title", contains("Dativ")));

			mvc.perform(as(anna, delete("/api/study-sessions/{id}/vocabulary/{item}", lesson, dog)))
				.andExpect(status().isNoContent());
			mvc.perform(as(anna, delete("/api/study-sessions/{id}/vocabulary/{item}", lesson, dog)))
				.andExpect(status().isNotFound());
			mvc.perform(as(anna, delete("/api/study-sessions/{id}/grammar-topics/{topic}", lesson, dative)))
				.andExpect(status().isNoContent());
			mvc.perform(as(anna, get("/api/study-sessions/{id}", lesson)))
				.andExpect(jsonPath("$.vocabularyCount").value(1))
				.andExpect(jsonPath("$.grammarTopicCount").value(0));
			// The word itself is still there
			mvc.perform(as(anna, get("/api/vocabulary/{id}", dog))).andExpect(status().isOk());
		}

		@Test
		void onlyAttachesYourOwnMaterial() throws Exception {
			UUID lesson = session(anna, "{\"title\":\"Lesson 5\"}");
			UUID bens = word(ben, "Bens Wort");

			attachWords(anna, lesson, bens).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("vocabularyItemIds"));
			send(anna, post("/api/study-sessions/{id}/vocabulary", lesson), "{\"vocabularyItemIds\":[]}")
				.andExpect(status().isBadRequest());
			// And only to your own sessions
			UUID mine = word(anna, "der Hund");
			UUID bensLesson = session(ben, "{\"title\":\"Ben's lesson\"}");
			attachWords(anna, bensLesson, mine).andExpect(status().isNotFound());
		}

		@Test
		void deletingASessionKeepsItsMaterial() throws Exception {
			UUID lesson = session(anna, "{\"title\":\"Lesson 5\"}");
			UUID dog = word(anna, "der Hund");
			attachWords(anna, lesson, dog).andExpect(status().isOk());

			mvc.perform(as(anna, delete("/api/study-sessions/{id}", lesson))).andExpect(status().isNoContent());
			mvc.perform(as(anna, get("/api/vocabulary/{id}", dog))).andExpect(status().isOk());
		}

	}

}
