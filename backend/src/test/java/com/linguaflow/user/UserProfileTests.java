package com.linguaflow.user;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.testsupport.TestUsers;
import com.linguaflow.testsupport.TestUsers.TestUser;
import jakarta.servlet.http.Cookie;
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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/users/me end to end (USR-01). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class UserProfileTests {

	private static final String EMAIL = "profile@example.de";

	private static final String PASSWORD = "correct-horse-battery";

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

	private TestUser me;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		me = new TestUsers(registration, accessTokens, jdbc).learner(EMAIL);
	}

	// ---- GET / PATCH ----------------------------------------------------------------------

	@Test
	void returnsTheProfileWithoutSecrets() throws Exception {
		authed(get("/api/users/me")).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(me.id().toString()))
			.andExpect(jsonPath("$.email").value(EMAIL))
			.andExpect(jsonPath("$.displayName").value("Test Learner"))
			.andExpect(jsonPath("$.role").value("USER"))
			.andExpect(jsonPath("$.emailVerified").value(false))
			.andExpect(jsonPath("$.interfaceLanguage").value("en"))
			.andExpect(jsonPath("$.timezone").value("UTC"))
			.andExpect(jsonPath("$.passwordHash").doesNotExist());
	}

	@Test
	void requiresAToken() throws Exception {
		mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void patchChangesOnlyTheGivenFields() throws Exception {
		authed(patch("/api/users/me"), "{\"timezone\":\"Europe/Berlin\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.timezone").value("Europe/Berlin"))
			.andExpect(jsonPath("$.displayName").value("Test Learner"));

		authed(patch("/api/users/me"), "{\"displayName\":\" Anna \",\"interfaceLanguage\":\"de\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.displayName").value("Anna"))
			.andExpect(jsonPath("$.interfaceLanguage").value("de"))
			.andExpect(jsonPath("$.timezone").value("Europe/Berlin"));
	}

	@ParameterizedTest(name = "{2}")
	@CsvSource(delimiter = '|', textBlock = """
			{"timezone":"Mars/Olympus"}       | timezone          | unknown zone
			{"timezone":"+02:00"}             | timezone          | offset instead of a region
			{"interfaceLanguage":"xx"}        | interfaceLanguage | unknown language
			{"interfaceLanguage":"German"}    | interfaceLanguage | not a code
			{"displayName":"   "}             | displayName       | blank name
			""")
	void rejectsInvalidValues(String body, String field, String caseName) throws Exception {
		authed(patch("/api/users/me"), body).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value(field));
	}

	// ---- password change ------------------------------------------------------------------

	@Test
	void changingThePasswordKeepsThisClientAndEndsOtherSessions() throws Exception {
		String otherDevice = refreshCookie(login(PASSWORD).andExpect(status().isOk()));

		ResultActions changed = authed(put("/api/users/me/password"),
				"{\"currentPassword\":\"%s\",\"newPassword\":\"a-brand-new-passphrase\"}".formatted(PASSWORD))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isNotEmpty())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("lf_refresh=")));
		String thisDevice = refreshCookie(changed);

		refresh(otherDevice).andExpect(status().isUnauthorized());
		refresh(thisDevice).andExpect(status().isOk());
		login(PASSWORD).andExpect(status().isUnauthorized());
		login("a-brand-new-passphrase").andExpect(status().isOk());
	}

	@Test
	void wrongCurrentPasswordIsAFieldErrorNotA401() throws Exception {
		authed(put("/api/users/me/password"),
				"{\"currentPassword\":\"not-my-password\",\"newPassword\":\"a-brand-new-passphrase\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("currentPassword"))
			.andExpect(jsonPath("$.errors[0].message").value("is incorrect"));
	}

	@Test
	void newPasswordMustDifferAndMeetThePolicy() throws Exception {
		authed(put("/api/users/me/password"),
				"{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(PASSWORD, PASSWORD))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("newPassword"));
		authed(put("/api/users/me/password"), "{\"currentPassword\":\"%s\",\"newPassword\":\"short\"}".formatted(PASSWORD))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("newPassword"));
	}

	// ---- deletion -------------------------------------------------------------------------

	@Test
	void deletingTheAccountRemovesAllDataAndEndsTheSession() throws Exception {
		jdbc.update("insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma) "
				+ "values (gen_random_uuid(), ?, 'de', 'Hund', 'hund')", me.id());
		String session = refreshCookie(login(PASSWORD));

		authed(delete("/api/users/me"), "{\"password\":\"%s\"}".formatted(PASSWORD)).andExpect(status().isNoContent())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

		assertThat(users.findById(me.id())).isEmpty();
		assertThat(jdbc.queryForObject("select count(*) from vocabulary_items where user_id = ?", Integer.class,
				me.id()))
			.isZero();
		assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ?", Integer.class, me.id()))
			.isZero();
		login(PASSWORD).andExpect(status().isUnauthorized());
		refresh(session).andExpect(status().isUnauthorized());
		// The access token is still cryptographically valid for a few minutes, but the account is gone
		authed(get("/api/users/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void deletionNeedsTheCorrectPassword() throws Exception {
		authed(delete("/api/users/me"), "{\"password\":\"not-my-password\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("password"));
		assertThat(users.findById(me.id())).isPresent();
	}

	// ---- helpers --------------------------------------------------------------------------

	private ResultActions authed(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, me.bearer()));
	}

	private ResultActions authed(MockHttpServletRequestBuilder request, String json) throws Exception {
		return authed(request.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions login(String password) throws Exception {
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(EMAIL, password)));
	}

	private ResultActions refresh(String token) throws Exception {
		return mvc.perform(post("/api/auth/refresh").cookie(new Cookie("lf_refresh", token)));
	}

	private static String refreshCookie(ResultActions result) {
		String header = result.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
		return header.substring("lf_refresh=".length(), header.indexOf(';'));
	}

}
