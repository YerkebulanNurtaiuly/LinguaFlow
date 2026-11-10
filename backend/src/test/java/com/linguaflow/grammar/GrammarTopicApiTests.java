package com.linguaflow.grammar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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

import java.util.List;
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

/** The grammar topic HTTP API end to end (GRM-01). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class GrammarTopicApiTests {

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

	private ResultActions create(TestUser user, String json) throws Exception {
		return send(user, post("/api/grammar-topics"), json);
	}

	private UUID topic(TestUser user, String json) throws Exception {
		String body = create(user, json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	private ResultActions list(TestUser user, String query) throws Exception {
		return mvc.perform(as(user, get("/api/grammar-topics" + query)));
	}

	private List<String> history(UUID topicId) {
		return jdbc.queryForList("""
				select coalesce(from_status, '-') || '>' || to_status || ':' || reason
				from grammar_topic_status_history where grammar_topic_id = ? order by changed_at, to_status
				""", String.class, topicId);
	}

	@Nested
	class Create {

		@Test
		void addsATopicWithItsDetails() throws Exception {
			create(anna, """
					{"title":"  Dativ ","description":"Indirect objects","personalNotes":"- mit\\n- bei\\n",
					 "cefrLevel":"A2","status":"LEARNING"}
					""")
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", containsString("/api/grammar-topics/")))
				.andExpect(jsonPath("$.title").value("Dativ"))
				.andExpect(jsonPath("$.description").value("Indirect objects"))
				.andExpect(jsonPath("$.personalNotes").value("- mit\n- bei"))
				.andExpect(jsonPath("$.cefrLevel").value("A2"))
				.andExpect(jsonPath("$.status").value("LEARNING"))
				.andExpect(jsonPath("$.aiExplanation").value(nullValue()))
				.andExpect(jsonPath("$.version").value(0));
		}

		@Test
		void startsAsNewAndRecordsIt() throws Exception {
			UUID id = topic(anna, "{\"title\":\"Akkusativ\"}");

			mvc.perform(as(anna, get("/api/grammar-topics/{id}", id))).andExpect(jsonPath("$.status").value("NEW"));
			assertThat(history(id)).containsExactly("->NEW:CREATED");
		}

		@Test
		void rejectsATitleYouAlreadyHave() throws Exception {
			UUID genitive = topic(anna, "{\"title\":\"Der Genitiv\"}");

			for (String variant : List.of("genitiv", "  DER   GENITIV ")) {
				create(anna, "{\"title\":\"" + variant + "\"}").andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("GRAMMAR_TOPIC_DUPLICATE"))
					.andExpect(jsonPath("$.existingId").value(genitive.toString()));
			}
			// Titles are per user
			create(ben, "{\"title\":\"Genitiv\"}").andExpect(status().isCreated());
		}

		@Test
		void neverStartsAsMastered() throws Exception {
			create(anna, "{\"title\":\"Dativ\",\"status\":\"MASTERED\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("status"));
		}

		@Test
		void validatesTheBody() throws Exception {
			create(anna, "{\"title\":\"  \"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("title"));
			create(anna, "{\"title\":\"" + "x".repeat(201) + "\"}").andExpect(status().isBadRequest());
			create(anna, "{\"title\":\"Dativ\",\"cefrLevel\":\"Z9\"}").andExpect(status().isBadRequest());
		}

		@Test
		void needsALanguageFirst() throws Exception {
			TestUser newcomer = testUsers.user("new@example.de");
			create(newcomer, "{\"title\":\"Dativ\"}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NO_ACTIVE_LANGUAGE"));
		}

	}

	@Nested
	class ListAndSearch {

		@BeforeEach
		void topics() throws Exception {
			topic(anna, "{\"title\":\"Präsens\",\"cefrLevel\":\"A1\",\"status\":\"LEARNED\"}");
			topic(anna, "{\"title\":\"Dativ\",\"cefrLevel\":\"A2\",\"description\":\"mit, bei, nach\"}");
			topic(anna, "{\"title\":\"Konjunktiv II\",\"cefrLevel\":\"B1\",\"status\":\"LEARNING\"}");
			topic(ben, "{\"title\":\"Bens Thema\"}");
		}

		@Test
		void listsYourTopicsNewestFirst() throws Exception {
			list(anna, "").andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.content[*].title", contains("Konjunktiv II", "Dativ", "Präsens")));
		}

		@Test
		void filtersByLevelAndStatus() throws Exception {
			list(anna, "?cefrLevel=A1&cefrLevel=A2&sort=title").andExpect(
					jsonPath("$.content[*].title", contains("Dativ", "Präsens")));
			list(anna, "?status=NEW").andExpect(jsonPath("$.content[*].title", contains("Dativ")));
			list(anna, "?status=LEARNING&cefrLevel=A1").andExpect(jsonPath("$.totalElements").value(0));
		}

		@Test
		void searchesTitleAndDescription() throws Exception {
			list(anna, "?q=konjunktiv").andExpect(jsonPath("$.content[*].title", contains("Konjunktiv II")));
			list(anna, "?q=BEI").andExpect(jsonPath("$.content[*].title", contains("Dativ")));
		}

		@Test
		void sortsByLevelAndRejectsUnknownSorts() throws Exception {
			list(anna, "?sort=cefrLevel,desc")
				.andExpect(jsonPath("$.content[*].title", contains("Konjunktiv II", "Dativ", "Präsens")))
				.andExpect(jsonPath("$.sort[0].property").value("cefrLevel"));
			list(anna, "?sort=personalNotes").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("sort"));
		}

	}

	@Nested
	class ReadUpdateDelete {

		@Test
		void updatesOnlyTheGivenFieldsAndRecordsStatusChanges() throws Exception {
			UUID id = topic(anna, "{\"title\":\"Dativ\",\"description\":\"old\",\"personalNotes\":\"notes\"}");

			send(anna, patch("/api/grammar-topics/{id}", id), "{\"status\":\"LEARNING\",\"personalNotes\":\"\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("LEARNING"))
				.andExpect(jsonPath("$.description").value("old"))
				.andExpect(jsonPath("$.personalNotes").value(nullValue()))
				.andExpect(jsonPath("$.version").value(1));
			send(anna, patch("/api/grammar-topics/{id}", id), "{\"status\":\"LEARNED\"}").andExpect(status().isOk());

			assertThat(history(id)).containsExactly("->NEW:CREATED", "NEW>LEARNING:MANUAL", "LEARNING>LEARNED:MANUAL");
		}

		@Test
		void renamesUnlessTheTitleIsTaken() throws Exception {
			UUID dative = topic(anna, "{\"title\":\"Dativ\"}");
			UUID other = topic(anna, "{\"title\":\"Akkusativ\"}");

			send(anna, patch("/api/grammar-topics/{id}", other), "{\"title\":\"dativ\"}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.existingId").value(dative.toString()));
			// Changing only the case of its own title is fine
			send(anna, patch("/api/grammar-topics/{id}", dative), "{\"title\":\"DATIV\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("DATIV"));
		}

		@Test
		void refusesMasteredAndBlankTitles() throws Exception {
			UUID id = topic(anna, "{\"title\":\"Dativ\"}");
			send(anna, patch("/api/grammar-topics/{id}", id), "{\"status\":\"MASTERED\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("status"));
			send(anna, patch("/api/grammar-topics/{id}", id), "{\"title\":\" \"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("title"));
		}

		@Test
		void deletesATopicWithItsHistory() throws Exception {
			UUID id = topic(anna, "{\"title\":\"Dativ\"}");

			mvc.perform(as(anna, delete("/api/grammar-topics/{id}", id))).andExpect(status().isNoContent());

			mvc.perform(as(anna, get("/api/grammar-topics/{id}", id))).andExpect(status().isNotFound());
			assertThat(history(id)).isEmpty();
		}

		@Test
		void onlyTheOwnerCanReadChangeOrDeleteATopic() throws Exception {
			UUID id = topic(anna, "{\"title\":\"Dativ\"}");

			OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.GET, "/api/grammar-topics/{id}", id,
					anna.accessToken(), ben.accessToken());
			OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.PATCH, "/api/grammar-topics/{id}", id,
					anna.accessToken(), ben.accessToken(), "{\"description\":\"mine\"}");
			OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.DELETE, "/api/grammar-topics/{id}", id,
					anna.accessToken(), ben.accessToken());
		}

	}

}
