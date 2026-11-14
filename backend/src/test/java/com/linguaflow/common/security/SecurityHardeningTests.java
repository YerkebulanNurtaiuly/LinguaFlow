package com.linguaflow.common.security;

import java.util.UUID;

import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.common.config.SecurityConfig;
import com.linguaflow.testsupport.IntegrationTest;
import com.linguaflow.testsupport.TestData;
import com.linguaflow.testsupport.TestUsers;
import com.linguaflow.testsupport.TestUsers.TestUser;
import com.linguaflow.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-01's checks through the real stack: the security headers on every API response,
 * whether it succeeds or fails, and hostile input that must stay data (SQL meta-characters
 * in searches, sort names that aren't on the whitelist, malformed IDs).
 */
@IntegrationTest
class SecurityHardeningTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private UserRepository users;

	@Autowired
	private RegistrationService registration;

	@Autowired
	private AccessTokenService accessTokens;

	private TestUser anna;

	private TestUser ben;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		TestUsers testUsers = new TestUsers(registration, accessTokens, jdbc);
		anna = testUsers.learner("anna@example.de");
		ben = testUsers.learner("ben@example.de");
	}

	private static ResultActions expectSecurityHeaders(ResultActions result) throws Exception {
		return result.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("X-Frame-Options", "DENY"))
			.andExpect(header().string("Referrer-Policy", "no-referrer"))
			.andExpect(header().string("Permissions-Policy", SecurityConfig.PERMISSIONS_POLICY))
			.andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"));
	}

	@Test
	void apiResponsesCarryTheHeadersAndTheirOwnContentSecurityPolicy() throws Exception {
		expectSecurityHeaders(mvc.perform(get("/api/users/me").header("Authorization", anna.bearer())))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Security-Policy", SecurityConfig.API_CONTENT_SECURITY_POLICY));
		// Errors too, including the ones the filter chain writes
		expectSecurityHeaders(mvc.perform(get("/api/users/me"))).andExpect(status().isUnauthorized())
			.andExpect(header().string("Content-Security-Policy", SecurityConfig.API_CONTENT_SECURITY_POLICY));
		expectSecurityHeaders(mvc.perform(get("/api/admin/ai-usage").header("Authorization", anna.bearer())))
			.andExpect(status().isForbidden())
			.andExpect(header().exists("Content-Security-Policy"));
	}

	@Test
	void theApiPolicyStaysOffPagesOutsideTheApi() throws Exception {
		// Swagger UI (dev) lives outside /api and needs its scripts
		mvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(header().string("X-Frame-Options", "DENY"))
			.andExpect(header().doesNotExist("Content-Security-Policy"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "' or '1'='1", "x'; drop table vocabulary_items; --", "%", "_", "\\", "\" or 1=1 --" })
	void searchTextIsMatchedLiterallyAndStaysScopedToTheOwner(String q) throws Exception {
		TestData data = new TestData(jdbc);
		data.word(anna.id(), "der Hund").translation("dog").insert();
		data.word(ben.id(), "die Katze").translation("cat").insert();

		mvc.perform(get("/api/vocabulary").param("q", q).header("Authorization", anna.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(0));
		mvc.perform(get("/api/grammar-topics").param("q", q).header("Authorization", anna.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(0));
		mvc.perform(get("/api/flashcards").param("q", q).header("Authorization", anna.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(0));
		assertThat(jdbc.queryForObject("select count(*) from vocabulary_items", Integer.class)).isEqualTo(2);
	}

	@ParameterizedTest
	@ValueSource(strings = { "lemma;drop table users", "userId", "password_hash", "(select 1)", "lemma desc, 1" })
	void sortingAcceptsOnlyWhitelistedNames(String sort) throws Exception {
		for (String uri : new String[] { "/api/vocabulary", "/api/grammar-topics", "/api/flashcards" }) {
			mvc.perform(get(uri).param("sort", sort).header("Authorization", anna.bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("sort"));
		}
	}

	@Test
	void malformedIdsAreRejectedBeforeAnyQuery() throws Exception {
		mvc.perform(get("/api/vocabulary/{id}", "1 or 1=1").header("Authorization", anna.bearer()))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/vocabulary/{id}", UUID.randomUUID()).header("Authorization", anna.bearer()))
			.andExpect(status().isNotFound());
	}

}
