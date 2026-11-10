package com.linguaflow.vocabulary;

import static org.hamcrest.Matchers.contains;
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

/** Tags and tagging words over HTTP (VOC-03). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class TagApiTests {

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

	private ResultActions json(TestUser user, MockHttpServletRequestBuilder request, String body) throws Exception {
		return mvc.perform(as(user, request).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions createTag(TestUser user, String name) throws Exception {
		return json(user, post("/api/tags"), "{\"name\":\"" + name + "\"}");
	}

	private UUID tag(TestUser user, String name) throws Exception {
		return id(createTag(user, name).andExpect(status().isCreated()));
	}

	private UUID word(TestUser user, String json) throws Exception {
		return id(json(user, post("/api/vocabulary"), json).andExpect(status().isCreated()));
	}

	private static UUID id(ResultActions result) throws Exception {
		return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id"));
	}

	@Nested
	class Tags {

		@Test
		void createsAndListsTagsByNameWithWordCounts() throws Exception {
			UUID food = tag(anna, "Food");
			tag(anna, "chapter 3");
			word(anna, "{\"lemma\":\"der Apfel\",\"tagIds\":[\"" + food + "\"]}");
			word(anna, "{\"lemma\":\"das Brot\",\"tagIds\":[\"" + food + "\"]}");
			tag(ben, "Ben's");

			mvc.perform(as(anna, get("/api/tags")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].name", contains("chapter 3", "Food")))
				.andExpect(jsonPath("$[*].wordCount", contains(0, 2)));
		}

		@Test
		void returnsTheCreatedTag() throws Exception {
			createTag(anna, "  Chapter    3 ")
				.andExpect(status().isCreated())
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.name").value("Chapter 3"))
				.andExpect(jsonPath("$.wordCount").value(0));
		}

		@Test
		void rejectsANameTakenIgnoringCaseWithTheExistingId() throws Exception {
			UUID food = tag(anna, "Food");

			createTag(anna, "FOOD")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TAG_NAME_TAKEN"))
				.andExpect(jsonPath("$.existingId").value(food.toString()));
			// Names are per user
			createTag(ben, "Food").andExpect(status().isCreated());
		}

		@Test
		void validatesTheName() throws Exception {
			createTag(anna, "   ").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("name"));
			createTag(anna, "x".repeat(51)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("name"));
		}

		@Test
		void renamesATag() throws Exception {
			UUID food = tag(anna, "Food");
			UUID drinks = tag(anna, "Drinks");

			json(anna, patch("/api/tags/{id}", food), "{\"name\":\"food\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("food"));
			json(anna, patch("/api/tags/{id}", drinks), "{\"name\":\"Food\"}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TAG_NAME_TAKEN"))
				.andExpect(jsonPath("$.existingId").value(food.toString()));
		}

		@Test
		void deletingATagKeepsItsWords() throws Exception {
			UUID food = tag(anna, "Food");
			UUID apple = word(anna, "{\"lemma\":\"der Apfel\",\"tagIds\":[\"" + food + "\"]}");

			mvc.perform(as(anna, delete("/api/tags/{id}", food))).andExpect(status().isNoContent());

			mvc.perform(as(anna, get("/api/vocabulary/{id}", apple)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tags").isEmpty());
			mvc.perform(as(anna, get("/api/tags"))).andExpect(jsonPath("$").isEmpty());
		}

		@Test
		void needsALanguageFirst() throws Exception {
			TestUser newcomer = testUsers.user("new@example.de");
			mvc.perform(as(newcomer, get("/api/tags")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NO_ACTIVE_LANGUAGE"));
		}

		@Test
		void onlyTheOwnerCanRenameOrDeleteATag() throws Exception {
			UUID food = tag(anna, "Food");

			OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.PATCH, "/api/tags/{id}", food,
					anna.accessToken(), ben.accessToken(), "{\"name\":\"Essen\"}");
			OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.DELETE, "/api/tags/{id}", food,
					anna.accessToken(), ben.accessToken());
		}

	}

	@Nested
	class TaggingWords {

		@Test
		void tagsAWordOnCreateAndReplacesTagsOnEdit() throws Exception {
			UUID food = tag(anna, "Food");
			UUID fruit = tag(anna, "Fruit");
			UUID apple = word(anna, "{\"lemma\":\"der Apfel\",\"tagIds\":[\"" + food + "\",\"" + food + "\"]}");

			mvc.perform(as(anna, get("/api/vocabulary/{id}", apple)))
				.andExpect(jsonPath("$.tags[*].name", contains("Food")));

			json(anna, patch("/api/vocabulary/{id}", apple), "{\"tagIds\":[\"" + fruit + "\",\"" + food + "\"]}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tags[*].name", contains("Food", "Fruit")));
			// Absent tagIds leave the tags alone
			json(anna, patch("/api/vocabulary/{id}", apple), "{\"notes\":\"red\"}")
				.andExpect(jsonPath("$.tags[*].name", contains("Food", "Fruit")));
			mvc.perform(as(anna, get("/api/vocabulary?tagId=" + fruit))).andExpect(jsonPath("$.totalElements").value(1));

			json(anna, patch("/api/vocabulary/{id}", apple), "{\"tagIds\":[]}").andExpect(jsonPath("$.tags").isEmpty());
		}

		@Test
		void rejectsTagsThatAreNotYours() throws Exception {
			UUID bens = tag(ben, "Ben's");
			UUID apple = word(anna, "{\"lemma\":\"der Apfel\"}");

			json(anna, post("/api/vocabulary"), "{\"lemma\":\"die Birne\",\"tagIds\":[\"" + bens + "\"]}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("tagIds"));
			json(anna, patch("/api/vocabulary/{id}", apple), "{\"tagIds\":[\"" + UUID.randomUUID() + "\"]}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("tagIds"));
			mvc.perform(as(anna, get("/api/vocabulary?q=birne"))).andExpect(jsonPath("$.totalElements").value(0));
		}

		@Test
		void capsTheTagsPerWord() throws Exception {
			StringBuilder ids = new StringBuilder();
			for (int i = 0; i <= TagService.MAX_TAGS_PER_WORD; i++) {
				ids.append(i == 0 ? "" : ",").append('"').append(UUID.randomUUID()).append('"');
			}
			json(anna, post("/api/vocabulary"), "{\"lemma\":\"der Apfel\",\"tagIds\":[" + ids + "]}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("tagIds"));
		}

	}

}
