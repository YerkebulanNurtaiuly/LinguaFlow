package com.linguaflow.vocabulary;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
import tools.jackson.databind.json.JsonMapper;

/** Bulk vocabulary input over HTTP (VOC-04). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class BulkVocabularyApiTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

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

	@BeforeEach
	void setUp() {
		users.deleteAll();
		testUsers = new TestUsers(registration, accessTokens, jdbc);
		anna = testUsers.learner("anna@example.de");
	}

	@AfterEach
	void tearDown() {
		users.deleteAll();
	}

	private MockHttpServletRequestBuilder as(TestUser user, MockHttpServletRequestBuilder request) {
		return request.header(AUTHORIZATION, user.bearer());
	}

	private ResultActions postJson(TestUser user, String uri, Object body) throws Exception {
		return mvc.perform(as(user, post(uri)).contentType(MediaType.APPLICATION_JSON)
			.content(body instanceof String s ? s : JSON.writeValueAsString(body)));
	}

	private ResultActions preview(String text, String format) throws Exception {
		return postJson(anna, "/api/vocabulary/bulk",
				format == null ? java.util.Map.of("text", text) : java.util.Map.of("text", text, "format", format));
	}

	private UUID word(String json) throws Exception {
		String body = postJson(anna, "/api/vocabulary", json).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	@Nested
	class Preview {

		@Test
		void reportsEveryLineOfAHundredLineInput() throws Exception {
			UUID dog = word("{\"lemma\":\"der Hund\",\"partOfSpeech\":\"NOUN\"}");
			String words = IntStream.rangeClosed(1, 97).mapToObj(i -> "Wort" + i + " - word " + i)
				.collect(Collectors.joining("\n"));
			String text = "Hund - dog\n\n - nothing\nWort1\n" + words;

			preview(text, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.lines", hasSize(100)))
				.andExpect(jsonPath("$.newCount").value(97))
				.andExpect(jsonPath("$.duplicateCount").value(2))
				.andExpect(jsonPath("$.invalidCount").value(1))
				// "Hund" has no part of speech, so it matches your noun "der Hund"
				.andExpect(jsonPath("$.lines[0].line").value(1))
				.andExpect(jsonPath("$.lines[0].status").value("DUPLICATE"))
				.andExpect(jsonPath("$.lines[0].existingId").value(dog.toString()))
				.andExpect(jsonPath("$.lines[0].translation").value("dog"))
				.andExpect(jsonPath("$.lines[1].line").value(3))
				.andExpect(jsonPath("$.lines[1].status").value("INVALID"))
				.andExpect(jsonPath("$.lines[1].message").value("has no word"))
				.andExpect(jsonPath("$.lines[2].status").value("NEW"))
				.andExpect(jsonPath("$.lines[2].lemma").value("Wort1"))
				.andExpect(jsonPath("$.lines[3].line").value(5))
				.andExpect(jsonPath("$.lines[3].status").value("DUPLICATE"))
				.andExpect(jsonPath("$.lines[3].duplicateOfLine").value(4))
				.andExpect(jsonPath("$.lines[3].message").value("repeats line 4"))
				.andExpect(jsonPath("$.lines[99].lemma").value("Wort97"))
				.andExpect(jsonPath("$.lines[99].translation").value("word 97"));

			// Nothing was saved
			mvc.perform(as(anna, get("/api/vocabulary"))).andExpect(jsonPath("$.totalElements").value(1));
		}

		@Test
		void keepsWordsThatDifferInPartOfSpeech() throws Exception {
			word("{\"lemma\":\"laufen\",\"partOfSpeech\":\"VERB\"}");

			preview("lemma,pos,translation\ndas Laufen,noun,running\nlaufen,verb,to run", "CSV")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lines[*].status", contains("NEW", "DUPLICATE")))
				.andExpect(jsonPath("$.lines[0].partOfSpeech").value("NOUN"))
				.andExpect(jsonPath("$.lines[0].line").value(2));
		}

		@Test
		void rejectsTooManyLines() throws Exception {
			preview("Wort\n".repeat(BulkVocabularyParser.MAX_LINES + 1), null).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("text"));
		}

		@Test
		void rejectsAnEmptyInputOrUnknownFormat() throws Exception {
			preview("  \n ", null).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("text"));
			preview("Hund", "XML").andExpect(status().isBadRequest());
		}

		@Test
		void needsALanguageFirst() throws Exception {
			TestUser newcomer = testUsers.user("new@example.de");
			postJson(newcomer, "/api/vocabulary/bulk", "{\"text\":\"Hund\"}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NO_ACTIVE_LANGUAGE"));
		}

	}

	@Nested
	class Confirm {

		@Test
		void addsAHundredItemsInOneRequest() throws Exception {
			String items = IntStream.rangeClosed(1, 100)
				.mapToObj(i -> "{\"lemma\":\"Wort" + i + "\",\"translation\":\"word " + i + "\"}")
				.collect(Collectors.joining(","));

			postJson(anna, "/api/vocabulary/bulk/confirm", "{\"items\":[" + items + "]}").andExpect(status().isOk())
				.andExpect(jsonPath("$.createdCount").value(100))
				.andExpect(jsonPath("$.results", hasSize(100)))
				.andExpect(jsonPath("$.results[99].index").value(99))
				.andExpect(jsonPath("$.results[99].status").value("CREATED"))
				.andExpect(jsonPath("$.results[99].id").isNotEmpty());

			mvc.perform(as(anna, get("/api/vocabulary?q=wort100")))
				.andExpect(jsonPath("$.content[0].translation").value("word 100"))
				.andExpect(jsonPath("$.content[0].aiStatus").value("PENDING_AI"));
		}

		@Test
		void reportsDuplicatesAndAddsTheRest() throws Exception {
			UUID dog = word("{\"lemma\":\"der Hund\",\"partOfSpeech\":\"NOUN\"}");

			String body = postJson(anna, "/api/vocabulary/bulk/confirm", """
					{"items":[{"lemma":"Hund"},{"lemma":"die Katze","partOfSpeech":"NOUN"},{"lemma":"Katze"},
					          {"lemma":"laufen","cefrLevel":"A1"}]}
					""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.results[*].status", contains("DUPLICATE", "CREATED", "DUPLICATE", "CREATED")))
				.andExpect(jsonPath("$.results[0].existingId").value(dog.toString()))
				.andExpect(jsonPath("$.createdCount").value(2))
				.andExpect(jsonPath("$.duplicateCount").value(2))
				.andReturn()
				.getResponse()
				.getContentAsString();
			// The repeat points at the word the earlier item just created
			String cat = JsonPath.read(body, "$.results[1].id");
			org.assertj.core.api.Assertions.assertThat((String) JsonPath.read(body, "$.results[2].existingId"))
				.isEqualTo(cat);
		}

		@Test
		void tagsEveryAddedWord() throws Exception {
			String tagBody = postJson(anna, "/api/tags", "{\"name\":\"Kapitel 3\"}").andReturn()
				.getResponse()
				.getContentAsString();
			String tag = JsonPath.read(tagBody, "$.id");

			postJson(anna, "/api/vocabulary/bulk/confirm",
					"{\"items\":[{\"lemma\":\"der Apfel\"},{\"lemma\":\"die Birne\"}],\"tagIds\":[\"" + tag + "\"]}")
				.andExpect(jsonPath("$.createdCount").value(2));
			mvc.perform(as(anna, get("/api/vocabulary?tagId=" + tag))).andExpect(jsonPath("$.totalElements").value(2));
		}

		@Test
		void addsNothingWhenATagIsUnknown() throws Exception {
			postJson(anna, "/api/vocabulary/bulk/confirm",
					"{\"items\":[{\"lemma\":\"der Apfel\"}],\"tagIds\":[\"" + UUID.randomUUID() + "\"]}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("tagIds"));
			mvc.perform(as(anna, get("/api/vocabulary"))).andExpect(jsonPath("$.totalElements").value(0));
		}

		@Test
		void validatesTheItems() throws Exception {
			postJson(anna, "/api/vocabulary/bulk/confirm", "{\"items\":[]}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("items"));
			postJson(anna, "/api/vocabulary/bulk/confirm", "{\"items\":[{\"lemma\":\"ok\"},{\"lemma\":\" \"}]}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("items[1].lemma"));
		}

	}

}
