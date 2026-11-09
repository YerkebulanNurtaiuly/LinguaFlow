package com.linguaflow.common.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationRequest;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.testsupport.MutableClock;
import com.linguaflow.user.Role;
import com.linguaflow.user.User;
import com.linguaflow.user.UserRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The JWT filter chain end to end, with real tokens from AccessTokenService. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, SecurityTests.TestEndpoints.class, SecurityTests.ClockConfig.class })
@ActiveProfiles("test")
class SecurityTests {

	private static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");

	// Matches application-test.yml
	private static final String TEST_SECRET = "test-only-jwt-secret-not-used-outside-tests-0123456789";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AccessTokenService accessTokens;

	@Autowired
	private RegistrationService registration;

	@Autowired
	private UserRepository users;

	@Autowired
	private MutableClock clock;

	private User user;

	@BeforeEach
	void setUp() {
		clock.set(T0);
		users.deleteAll();
		user = registration.register(new RegistrationRequest("sec@example.de", "correct-horse-battery", "Sec"));
	}

	@Test
	void missingTokenIs401ProblemWithBearerChallenge() throws Exception {
		mvc.perform(get("/api/test/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void validTokenReachesTheEndpointWithTheCurrentUserId() throws Exception {
		withToken(get("/api/test/me"), accessTokens.issue(user).value()).andExpect(status().isOk())
			.andExpect(content().string("\"" + user.getId() + "\""));
	}

	@Test
	void tokenSignedWithAnotherKeyIsRejected() throws Exception {
		String forged = sign("another-secret-of-at-least-thirty-two-characters!", "linguaflow", "USER");
		expectInvalidToken(withToken(get("/api/test/me"), forged));
	}

	@Test
	void tokenFromAnotherIssuerIsRejected() throws Exception {
		expectInvalidToken(withToken(get("/api/test/me"), sign(TEST_SECRET, "someone-else", "USER")));
	}

	@Test
	void expiredTokenIsRejected() throws Exception {
		String token = accessTokens.issue(user).value();
		clock.advance(Duration.ofMinutes(17)); // 15 min lifetime + 60 s allowed clock skew
		expectInvalidToken(withToken(get("/api/test/me"), token));
	}

	@Test
	void garbageTokenIsRejected() throws Exception {
		expectInvalidToken(withToken(get("/api/test/me"), "not.a.jwt"));
	}

	@Test
	void userRoleCannotUseAdminEndpoints() throws Exception {
		withToken(get("/api/admin/test"), accessTokens.issue(user).value()).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	void adminRoleCanUseAdminEndpoints() throws Exception {
		user.promoteTo(Role.ADMIN);
		withToken(get("/api/admin/test"), accessTokens.issue(user).value()).andExpect(status().isOk());
	}

	@Test
	void tokenWithoutRoleHasNoAdminAccess() throws Exception {
		String noRole = sign(TEST_SECRET, "linguaflow", null);
		withToken(get("/api/test/me"), noRole).andExpect(status().isOk());
		withToken(get("/api/admin/test"), noRole).andExpect(status().isForbidden());
	}

	@Test
	void writesNeedNoCsrfTokenWithABearerToken() throws Exception {
		withToken(post("/api/test/write"), accessTokens.issue(user).value()).andExpect(status().isOk());
	}

	@Test
	void noServerSessionIsCreated() throws Exception {
		withToken(get("/api/test/me"), accessTokens.issue(user).value()).andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, nullValue()));
	}

	@Test
	void publicRoutesNeedNoToken() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
		mvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
	}

	private ResultActions withToken(
			org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String token)
			throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private void expectInvalidToken(ResultActions result) throws Exception {
		result.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.detail").value("Access token is invalid or expired"));
	}

	private String sign(String secret, String issuer, String role) {
		var encoder = new NimbusJwtEncoder(
				new ImmutableSecret<>(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
			.issuer(issuer)
			.subject(user.getId().toString())
			.issuedAt(T0)
			.expiresAt(T0.plus(Duration.ofMinutes(15)));
		if (role != null) {
			claims.claim("role", role);
		}
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
			.getTokenValue();
	}

	@RestController
	static class TestEndpoints {

		@GetMapping("/api/test/me")
		UUID me(@CurrentUserId UUID userId) {
			return userId;
		}

		@PostMapping("/api/test/write")
		String write(@CurrentUserId UUID userId) {
			return "ok";
		}

		@GetMapping("/api/admin/test")
		String admin() {
			return "admin";
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockConfig {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(T0);
		}

	}

}
