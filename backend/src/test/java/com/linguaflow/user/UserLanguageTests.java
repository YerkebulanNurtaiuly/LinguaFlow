package com.linguaflow.user;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ErrorCode;
import com.linguaflow.testsupport.TestUsers;
import com.linguaflow.testsupport.TestUsers.TestUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Languages and the user's learning languages end to end (USR-02). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class UserLanguageTests {

	private static final String GERMAN_WITH_ENGLISH = "{\"languageCode\":\"de\",\"explanationLanguageCode\":\"en\","
			+ "\"selfAssessedLevel\":\"A2\"}";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private RegistrationService registration;

	@Autowired
	private AccessTokenService accessTokens;

	@Autowired
	private ActiveLanguageService activeLanguages;

	@Autowired
	private JdbcTemplate jdbc;

	private TestUser me;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		me = new TestUsers(registration, accessTokens, jdbc).user("lang@example.de");
	}

	@AfterEach
	void removeTestLanguage() {
		users.deleteAll();
		jdbc.update("delete from languages where code = 'es'");
	}

	@Test
	void listsAvailableLanguagesWithTheirUses() throws Exception {
		authed(get("/api/languages")).andExpect(status().isOk())
			.andExpect(jsonPath("$[*].code").value(hasItems("de", "en")))
			.andExpect(jsonPath("$[?(@.code == 'de')].supportedAsTarget").value(hasItems(true)))
			.andExpect(jsonPath("$[?(@.code == 'en')].supportedAsTarget").value(hasItems(false)))
			.andExpect(jsonPath("$[?(@.code == 'en')].nativeName").value(hasItems("English")));
	}

	@Test
	void userCanLearnGermanWithEnglishExplanations() throws Exception {
		authed(post("/api/users/me/languages"), GERMAN_WITH_ENGLISH).andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.LOCATION, "/api/users/me/languages/de"))
			.andExpect(jsonPath("$.languageCode").value("de"))
			.andExpect(jsonPath("$.explanationLanguageCode").value("en"))
			.andExpect(jsonPath("$.selfAssessedLevel").value("A2"))
			.andExpect(jsonPath("$.active").value(true));

		authed(get("/api/users/me/languages")).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].languageCode").value("de"));
		assertThat(activeLanguages.requireActive(me.id())).isEqualTo("de");
	}

	@Test
	void addingTheSameLanguageTwiceIs409() throws Exception {
		authed(post("/api/users/me/languages"), GERMAN_WITH_ENGLISH).andExpect(status().isCreated());
		authed(post("/api/users/me/languages"), GERMAN_WITH_ENGLISH).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CONFLICT"));
	}

	@ParameterizedTest(name = "{2}")
	@CsvSource(delimiter = '|', textBlock = """
			{"languageCode":"en","explanationLanguageCode":"de"} | languageCode            | English cannot be learned yet
			{"languageCode":"xx","explanationLanguageCode":"en"} | languageCode            | unknown target
			{"languageCode":"de","explanationLanguageCode":"de"} | explanationLanguageCode | same as target
			{"languageCode":"de","explanationLanguageCode":"xx"} | explanationLanguageCode | unknown explanation language
			{"explanationLanguageCode":"en"}                     | languageCode            | missing target
			""")
	void rejectsInvalidChoices(String body, String field, String caseName) throws Exception {
		authed(post("/api/users/me/languages"), body).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value(field));
	}

	@Test
	void patchUpdatesLevelAndExplanationLanguage() throws Exception {
		addSpanishAsTarget();
		authed(post("/api/users/me/languages"), GERMAN_WITH_ENGLISH);

		authed(patch("/api/users/me/languages/de"), "{\"selfAssessedLevel\":\"B1\",\"explanationLanguageCode\":\"es\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.selfAssessedLevel").value("B1"))
			.andExpect(jsonPath("$.explanationLanguageCode").value("es"))
			.andExpect(jsonPath("$.active").value(true));
	}

	@Test
	void switchingTheActiveLanguageKeepsExactlyOneActive() throws Exception {
		addSpanishAsTarget();
		authed(post("/api/users/me/languages"), GERMAN_WITH_ENGLISH);
		authed(post("/api/users/me/languages"), "{\"languageCode\":\"es\",\"explanationLanguageCode\":\"en\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.active").value(false));

		authed(patch("/api/users/me/languages/es"), "{\"active\":true}").andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(true));

		assertThat(activeLanguages.requireActive(me.id())).isEqualTo("es");
		assertThat(jdbc.queryForObject("select count(*) from user_languages where user_id = ? and active", Integer.class,
				me.id()))
			.isEqualTo(1);
		authed(patch("/api/users/me/languages/es"), "{\"active\":false}").andExpect(status().isBadRequest());
	}

	@Test
	void removingALanguageDeletesItsDataAndActivatesAnother() throws Exception {
		addSpanishAsTarget();
		authed(post("/api/users/me/languages"), GERMAN_WITH_ENGLISH);
		authed(post("/api/users/me/languages"), "{\"languageCode\":\"es\",\"explanationLanguageCode\":\"en\"}");
		jdbc.update("insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma) "
				+ "values (gen_random_uuid(), ?, 'de', 'Hund', 'hund')", me.id());

		authed(delete("/api/users/me/languages/de")).andExpect(status().isNoContent());

		assertThat(jdbc.queryForObject("select count(*) from vocabulary_items where user_id = ?", Integer.class, me.id()))
			.isZero();
		assertThat(activeLanguages.requireActive(me.id())).isEqualTo("es");
		authed(delete("/api/users/me/languages/de")).andExpect(status().isNotFound());
	}

	@Test
	void requireActiveFailsBeforeOnboarding() {
		assertThatThrownBy(() -> activeLanguages.requireActive(me.id())).isInstanceOf(ApiException.class)
			.extracting(e -> ((ApiException) e).code())
			.isEqualTo(ErrorCode.NO_ACTIVE_LANGUAGE);
	}

	@Test
	void languagesOfOtherUsersAreNotReachable() throws Exception {
		TestUser other = new TestUsers(registration, accessTokens, jdbc).learner("other@example.de");
		authed(get("/api/users/me/languages")).andExpect(jsonPath("$.length()").value(0));
		authed(patch("/api/users/me/languages/de"), "{\"selfAssessedLevel\":\"C2\"}").andExpect(status().isNotFound());
		authed(delete("/api/users/me/languages/de")).andExpect(status().isNotFound());
		assertThat(activeLanguages.requireActive(other.id())).isEqualTo("de");
	}

	private void addSpanishAsTarget() {
		jdbc.update("insert into languages (code, name, native_name, supported_as_target, supported_as_explanation) "
				+ "values ('es', 'Spanish', 'Español', true, true) on conflict do nothing");
	}

	private ResultActions authed(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, me.bearer()));
	}

	private ResultActions authed(MockHttpServletRequestBuilder request, String json) throws Exception {
		return authed(request.contentType(MediaType.APPLICATION_JSON).content(json));
	}

}
