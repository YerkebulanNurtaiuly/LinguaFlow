package com.linguaflow.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.testsupport.MutableClock;
import com.linguaflow.user.User;
import com.linguaflow.user.UserRepository;
import jakarta.servlet.http.Cookie;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login, refresh rotation, reuse detection and logout against a real database. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, AuthFlowTests.ClockConfig.class })
@ActiveProfiles("test")
class AuthFlowTests {

	private static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");

	private static final String EMAIL = "learner@example.de";

	private static final String PASSWORD = "correct-horse-battery";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private RegistrationService registration;

	@Autowired
	private JwtDecoder jwtDecoder;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private MutableClock clock;

	private User user;

	@BeforeEach
	void setUp() {
		clock.set(T0);
		users.deleteAll();
		user = registration.register(new RegistrationRequest(EMAIL, PASSWORD, "Learner"));
	}

	// ---- login ----------------------------------------------------------------------------

	@Test
	void loginReturnsBearerTokenAndHardenedRefreshCookie() throws Exception {
		MvcResult result = login(EMAIL, PASSWORD).andExpect(status().isOk())
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.expiresIn").value(900))
			.andExpect(jsonPath("$.refreshToken").doesNotExist())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Secure")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/api/auth")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=2592000")))
			.andReturn();

		Jwt jwt = jwtDecoder.decode(accessToken(result));
		assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
		assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("linguaflow");
		assertThat(jwt.getExpiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(15)));

		String raw = refreshCookie(result);
		List<String> stored = jdbc.queryForList("select token_hash from refresh_tokens where user_id = ?", String.class,
				user.getId());
		assertThat(stored).containsExactly(RefreshTokenService.hash(raw)).doesNotContain(raw);
		assertThat(users.findById(user.getId()).orElseThrow().getLastLoginAt()).isEqualTo(T0);
	}

	@Test
	void loginEmailIgnoresCase() throws Exception {
		login("LEARNER@Example.DE", PASSWORD).andExpect(status().isOk());
	}

	@Test
	void wrongPasswordAndUnknownEmailLookTheSame() throws Exception {
		String wrongPassword = login(EMAIL, "not-the-password").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String unknownEmail = login("nobody@example.de", PASSWORD).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(detail(wrongPassword)).isEqualTo(detail(unknownEmail));
		assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Integer.class)).isZero();
	}

	@Test
	void disabledAccountCannotLogIn() throws Exception {
		disableUser();
		login(EMAIL, PASSWORD).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
	}

	@Test
	void accessTokenExpiresAfter15Minutes() throws Exception {
		String token = accessToken(login(EMAIL, PASSWORD).andReturn());

		clock.advance(Duration.ofMinutes(14));
		assertThat(jwtDecoder.decode(token).getSubject()).isEqualTo(user.getId().toString());

		clock.advance(Duration.ofMinutes(3)); // past expiry plus the decoder's 60 s clock skew
		assertThatThrownBy(() -> jwtDecoder.decode(token)).isInstanceOf(JwtValidationException.class);
	}

	// ---- refresh --------------------------------------------------------------------------

	@Test
	void refreshRotatesTheCookieAndIssuesANewAccessToken() throws Exception {
		String first = refreshCookie(login(EMAIL, PASSWORD).andReturn());

		clock.advance(Duration.ofMinutes(20));
		MvcResult refreshed = refresh(first).andExpect(status().isOk())
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andReturn();
		String second = refreshCookie(refreshed);

		assertThat(second).isNotEqualTo(first);
		assertThat(jwtDecoder.decode(accessToken(refreshed)).getIssuedAt()).isEqualTo(T0.plus(Duration.ofMinutes(20)));
		refresh(second).andExpect(status().isOk());
	}

	@Test
	void reusingARotatedTokenRevokesTheWholeFamily() throws Exception {
		String first = refreshCookie(login(EMAIL, PASSWORD).andReturn());
		String second = refreshCookie(refresh(first).andExpect(status().isOk()).andReturn());
		String otherDevice = refreshCookie(login(EMAIL, PASSWORD).andReturn());

		// An attacker (or a stale tab) replays the already-rotated first token
		refresh(first).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

		// ...so the legitimate successor is dead too
		refresh(second).andExpect(status().isUnauthorized());
		UUID family = jdbc.queryForObject("select family_id from refresh_tokens where token_hash = ?", UUID.class,
				RefreshTokenService.hash(first));
		assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where family_id = ? and revoked_at is null",
				Integer.class, family))
			.isZero();

		// A separate login (another device) is not affected
		refresh(otherDevice).andExpect(status().isOk());
	}

	@Test
	void expiredRefreshTokenIsRejected() throws Exception {
		String token = refreshCookie(login(EMAIL, PASSWORD).andReturn());
		clock.advance(Duration.ofDays(30).plusSeconds(1));
		refresh(token).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
	}

	@Test
	void unknownOrMissingRefreshTokenIsRejected() throws Exception {
		refresh("made-up-token").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
		mvc.perform(post("/api/auth/refresh"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
	}

	@Test
	void disabledUserCannotRefresh() throws Exception {
		String token = refreshCookie(login(EMAIL, PASSWORD).andReturn());
		disableUser();
		refresh(token).andExpect(status().isUnauthorized());
	}

	// ---- logout ---------------------------------------------------------------------------

	@Test
	void logoutRevokesTheSessionAndClearsTheCookie() throws Exception {
		String token = refreshCookie(login(EMAIL, PASSWORD).andReturn());

		mvc.perform(post("/api/auth/logout").cookie(new Cookie("lf_refresh", token)))
			.andExpect(status().isNoContent())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

		refresh(token).andExpect(status().isUnauthorized());
	}

	@Test
	void logoutIsIdempotent() throws Exception {
		mvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
		mvc.perform(post("/api/auth/logout").cookie(new Cookie("lf_refresh", "unknown")))
			.andExpect(status().isNoContent());
	}

	// ---- helpers --------------------------------------------------------------------------

	private ResultActions login(String email, String password) throws Exception {
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.header(HttpHeaders.USER_AGENT, "JUnit")
			.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
	}

	private ResultActions refresh(String refreshToken) throws Exception {
		return mvc.perform(post("/api/auth/refresh").cookie(new Cookie("lf_refresh", refreshToken)));
	}

	private void disableUser() {
		User loaded = users.findById(user.getId()).orElseThrow();
		loaded.disable();
		users.save(loaded);
	}

	private static String accessToken(MvcResult result) throws Exception {
		String body = result.getResponse().getContentAsString();
		return body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
	}

	private static String refreshCookie(MvcResult result) {
		String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(header).startsWith("lf_refresh=");
		return header.substring("lf_refresh=".length(), header.indexOf(';'));
	}

	private static String detail(String problemJson) {
		return problemJson.replaceAll(".*\"detail\":\"([^\"]+)\".*", "$1");
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
