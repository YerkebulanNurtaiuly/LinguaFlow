package com.linguaflow.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** CSV export and import over HTTP (VOC-07). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class VocabularyTransferApiTests {

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

	private String postJson(TestUser user, String uri, String json) throws Exception {
		return mvc.perform(as(user, post(uri)).contentType(MediaType.APPLICATION_JSON).content(json))
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private String exportCsv(TestUser user, String query) throws Exception {
		byte[] bytes = mvc.perform(as(user, get("/api/vocabulary/export" + query)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private ResultActions importCsv(TestUser user, String csv) throws Exception {
		return importBytes(user, csv.getBytes(StandardCharsets.UTF_8));
	}

	private ResultActions importBytes(TestUser user, byte[] bytes) throws Exception {
		return mvc.perform(multipart("/api/vocabulary/import")
			.file(new MockMultipartFile("file", "vocabulary.csv", "text/csv", bytes))
			.header(AUTHORIZATION, user.bearer()));
	}

	private void seedAnna() throws Exception {
		String animals = JsonPath.read(postJson(anna, "/api/tags", "{\"name\":\"Tiere\"}"), "$.id");
		String chapter = JsonPath.read(postJson(anna, "/api/tags", "{\"name\":\"Kapitel 3\"}"), "$.id");
		postJson(anna, "/api/vocabulary", """
				{"lemma":"der Hund","partOfSpeech":"NOUN","translation":"dog","gender":"MASCULINE",
				 "plural":"die Hunde","cefrLevel":"A1","status":"LEARNED","notes":"bellt, oft \\"laut\\"\\nzweite Zeile",
				 "tagIds":["%s","%s"]}
				""".formatted(animals, chapter));
		postJson(anna, "/api/vocabulary", "{\"lemma\":\"-ung\",\"translation\":\"=noun suffix\"}");
		postJson(anna, "/api/vocabulary", "{\"lemma\":\"laufen\",\"partOfSpeech\":\"VERB\",\"tagIds\":[\"" + chapter
				+ "\"]}");
	}

	@Nested
	class Export {

		@Test
		void writesACsvFileOldestFirst() throws Exception {
			seedAnna();

			mvc.perform(as(anna, get("/api/vocabulary/export")))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", containsString("text/csv")))
				.andExpect(header().string("Content-Disposition",
						containsString("attachment; filename=\"linguaflow-vocabulary-de-")));
			String csv = exportCsv(anna, "");

			assertThat(csv).startsWith("﻿lemma,translation,partOfSpeech,gender,plural,cefrLevel,status,notes,tags\r\n");
			assertThat(csv.split("\r\n")).hasSize(4);
			assertThat(csv).contains("der Hund,dog,NOUN,MASCULINE,die Hunde,A1,LEARNED,\"bellt, oft \"\"laut\"\"\nzweite Zeile\",Kapitel 3|Tiere\r\n")
				.contains("'-ung,'=noun suffix,,,,,LEARNING,,\r\n");
		}

		@Test
		void appliesTheListFilters() throws Exception {
			seedAnna();
			assertThat(exportCsv(anna, "?partOfSpeech=VERB").split("\r\n")).hasSize(2);
			assertThat(exportCsv(anna, "?q=hund")).contains("der Hund").doesNotContain("laufen");
		}

		@Test
		void onlyExportsYourOwnWords() throws Exception {
			seedAnna();
			assertThat(exportCsv(ben, "").split("\r\n")).hasSize(1);
		}

	}

	@Nested
	class ImportCsv {

		@Test
		void anExportImportsBackUnchanged() throws Exception {
			seedAnna();
			String csv = exportCsv(anna, "");

			importCsv(ben, csv).andExpect(status().isOk())
				.andExpect(jsonPath("$.createdCount").value(3))
				.andExpect(jsonPath("$.createdTagCount").value(2))
				.andExpect(jsonPath("$.rows[*].line", contains(2, 4, 5)));

			assertThat(exportCsv(ben, "")).isEqualTo(csv);
			// Importing again finds every word already there
			importCsv(ben, csv).andExpect(jsonPath("$.duplicateCount").value(3))
				.andExpect(jsonPath("$.createdTagCount").value(0));
		}

		@Test
		void reportsEachRow() throws Exception {
			String existing = JsonPath.read(postJson(anna, "/api/vocabulary",
					"{\"lemma\":\"der Hund\",\"partOfSpeech\":\"NOUN\"}"), "$.id");

			importCsv(anna, """
					lemma,partOfSpeech,gender,status,tags
					Hund,NOUN,,,
					laufen,VERB,FEMININE,,
					die Katze,NOUN,FEMININE,MASTERED,tiere
					,,,,
					das Haus,thing,,,
					""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rows[*].status", contains("DUPLICATE", "INVALID", "CREATED", "INVALID")))
				.andExpect(jsonPath("$.rows[0].existingId").value(existing))
				.andExpect(jsonPath("$.rows[1].message").value("gender only nouns have a gender"))
				.andExpect(jsonPath("$.rows[2].message").value(containsString("imported as LEARNED")))
				.andExpect(jsonPath("$.rows[3].line").value(6))
				.andExpect(jsonPath("$.rows[3].message").value("unknown part of speech 'thing'"))
				.andExpect(jsonPath("$.createdTagCount").value(1));

			mvc.perform(as(anna, get("/api/vocabulary?q=katze")))
				.andExpect(jsonPath("$.content[0].status").value("LEARNED"))
				.andExpect(jsonPath("$.content[0].tags[0].name").value("tiere"));
		}

		@Test
		void reusesTagsByNameIgnoringCase() throws Exception {
			String animals = JsonPath.read(postJson(anna, "/api/tags", "{\"name\":\"Tiere\"}"), "$.id");

			importCsv(anna, "lemma,tags\nder Hund,TIERE\n").andExpect(jsonPath("$.createdTagCount").value(0));
			mvc.perform(as(anna, get("/api/vocabulary?tagId=" + animals))).andExpect(jsonPath("$.totalElements").value(1));
		}

		@Test
		void rejectsFilesItCannotRead() throws Exception {
			importCsv(anna, "der Hund,dog\n").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("file"));
			importBytes(anna, new byte[] { 'l', 'e', 'm', 'm', 'a', '\n', (byte) 0xC3, (byte) 0x28 })
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].message").value("must be UTF-8 text"));
			importCsv(anna, "lemma\n" + "Wort\n".repeat(VocabularyTransferService.MAX_IMPORT_ROWS + 1))
				.andExpect(status().isBadRequest());
			mvc.perform(multipart("/api/vocabulary/import").header(AUTHORIZATION, anna.bearer())).andExpect(status().isBadRequest());
		}

		@Test
		void needsALanguageFirst() throws Exception {
			TestUser newcomer = testUsers.user("new@example.de");
			importCsv(newcomer, "lemma\nHund\n").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NO_ACTIVE_LANGUAGE"));
			mvc.perform(as(newcomer, get("/api/vocabulary/export"))).andExpect(status().isConflict());
		}

	}

	@Test
	void tagNamesCannotContainTheTagSeparator() throws Exception {
		mvc.perform(as(anna, post("/api/tags")).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a|b\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].message").value("must not contain |"));
	}

}
