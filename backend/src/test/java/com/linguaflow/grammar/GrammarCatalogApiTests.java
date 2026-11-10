package com.linguaflow.grammar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationService;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The grammar topic catalog (GRM-03): its seed data and picking from it. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class GrammarCatalogApiTests {

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

	private UUID catalogId(String slug) {
		return jdbc.queryForObject("select id from grammar_topic_catalog where language_code = 'de' and slug = ?",
				UUID.class, slug);
	}

	private ResultActions add(TestUser user, UUID catalogId, String body) throws Exception {
		MockHttpServletRequestBuilder request = as(user, post("/api/grammar-topics/catalog/{id}", catalogId));
		return mvc.perform(body == null ? request
				: request.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	@Nested
	class SeedData {

		@Test
		void coversEveryLevelFromA1ToC1() {
			Map<String, Integer> perLevel = new HashMap<>();
			jdbc.query("select cefr_level, count(*) as n from grammar_topic_catalog where language_code = 'de' "
					+ "group by cefr_level", rs -> {
						perLevel.put(rs.getString("cefr_level"), rs.getInt("n"));
					});
			assertThat(perLevel).containsOnlyKeys("A1", "A2", "B1", "B2", "C1");
			assertThat(perLevel.values().stream().mapToInt(Integer::intValue).sum()).isGreaterThanOrEqualTo(45);
		}

		@Test
		void titlesStayDistinctAfterNormalization() {
			List<String> titles = jdbc.queryForList("select title from grammar_topic_catalog where language_code = 'de'",
					String.class);
			assertThat(titles.stream().map(title -> TitleNormalizer.normalize("de", title))).doesNotHaveDuplicates();
		}

		@Test
		void idsAreDerivedFromTheSlugSoTheyAreTheSameEverywhere() {
			UUID expected = jdbc.queryForObject("select md5('grammar-catalog:de:dativ')::uuid", UUID.class);
			assertThat(catalogId("dativ")).isEqualTo(expected);
		}

	}

	@Nested
	class Listing {

		@Test
		void listsTheCatalogByLevelThenTitle() throws Exception {
			String body = mvc.perform(as(anna, get("/api/grammar-topics/catalog")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].cefrLevel").value("A1"))
				.andExpect(jsonPath("$[-1].cefrLevel").value("C1"))
				.andExpect(jsonPath("$[*].topicId", everyItem(is(nullValue()))))
				.andReturn()
				.getResponse()
				.getContentAsString();
			List<String> levels = JsonPath.read(body, "$[*].cefrLevel");
			assertThat(levels).isSorted();
		}

		@Test
		void filtersByLevel() throws Exception {
			mvc.perform(as(anna, get("/api/grammar-topics/catalog?cefrLevel=B2&cefrLevel=C1")))
				.andExpect(jsonPath("$[*].cefrLevel", everyItem(org.hamcrest.Matchers.oneOf("B2", "C1"))))
				.andExpect(jsonPath("$[*].slug", hasItem("konjunktiv-i")));
		}

		@Test
		void marksTopicsYouAlreadyHaveEvenIfTyped() throws Exception {
			String typed = mvc.perform(as(anna, post("/api/grammar-topics")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"der Genitiv\"}"))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();

			mvc.perform(as(anna, get("/api/grammar-topics/catalog?cefrLevel=B1")))
				.andExpect(jsonPath("$[?(@.slug == 'genitiv')].topicId").value(JsonPath.<String>read(typed, "$.id")));
			// Someone else's topics don't count
			mvc.perform(as(ben, get("/api/grammar-topics/catalog?cefrLevel=B1")))
				.andExpect(jsonPath("$[?(@.slug == 'genitiv')].topicId").value(hasItem(nullValue())));
		}

		@Test
		void needsALanguageFirst() throws Exception {
			TestUser newcomer = testUsers.user("new@example.de");
			mvc.perform(as(newcomer, get("/api/grammar-topics/catalog"))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NO_ACTIVE_LANGUAGE"));
		}

	}

	@Nested
	class Adding {

		@Test
		void addsTheEntryAsYourTopic() throws Exception {
			UUID dative = catalogId("dativ");

			String body = add(anna, dative, null).andExpect(status().isCreated())
				.andExpect(jsonPath("$.title").value("Dativ"))
				.andExpect(jsonPath("$.cefrLevel").value("A2"))
				.andExpect(jsonPath("$.status").value("NEW"))
				.andExpect(jsonPath("$.description").isNotEmpty())
				.andExpect(jsonPath("$.catalogTopicId").value(dative.toString()))
				.andReturn()
				.getResponse()
				.getContentAsString();

			mvc.perform(as(anna, get("/api/grammar-topics/catalog?cefrLevel=A2")))
				.andExpect(jsonPath("$[?(@.slug == 'dativ')].topicId").value(JsonPath.<String>read(body, "$.id")));
		}

		@Test
		void takesAStartingStatusButNeverMastered() throws Exception {
			add(anna, catalogId("perfekt"), "{\"status\":\"LEARNING\"}").andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("LEARNING"));
			add(anna, catalogId("praesens"), "{\"status\":\"MASTERED\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("status"));
		}

		@Test
		void rejectsAnEntryYouAlreadyHave() throws Exception {
			String first = add(anna, catalogId("dativ"), null).andReturn().getResponse().getContentAsString();

			add(anna, catalogId("dativ"), null).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("GRAMMAR_TOPIC_DUPLICATE"))
				.andExpect(jsonPath("$.existingId").value(JsonPath.<String>read(first, "$.id")));
			// Ben has his own topics
			add(ben, catalogId("dativ"), null).andExpect(status().isCreated());
		}

		@Test
		void rejectsAnUnknownEntry() throws Exception {
			add(anna, UUID.randomUUID(), null).andExpect(status().isNotFound());
		}

	}

}
