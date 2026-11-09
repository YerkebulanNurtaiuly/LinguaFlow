package com.linguaflow.auth;

import java.time.Duration;
import java.time.Instant;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.testsupport.MutableClock;
import com.linguaflow.testsupport.RecordingMailer;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Email verification and password reset end to end (AUTH-07). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, AccountFlowTests.Config.class })
@ActiveProfiles("test")
class AccountFlowTests {

	private static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");

	private static final String EMAIL = "flow@example.de";

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
	private RecordingMailer mailer;

	@Autowired
	private MutableClock clock;

	private User user;

	@BeforeEach
	void setUp() {
		clock.set(T0);
		users.deleteAll();
		mailer.clear();
		user = registration.register(new RegistrationRequest(EMAIL, PASSWORD, "Flow"));
	}

	// ---- email verification ---------------------------------------------------------------

	@Test
	void registrationSendsAVerificationLinkThatWorksOnce() throws Exception {
		assertThat(mailer.lastTo(EMAIL).subject()).contains("Confirm your email");
		assertThat(mailer.lastTo(EMAIL).body()).contains("http://localhost:5173/verify-email?token=");
		String token = mailer.lastTokenFor(EMAIL);

		post("/api/auth/verify-email", "{\"token\":\"%s\"}".formatted(token)).andExpect(status().isNoContent());
		assertThat(reloadUser().getEmailVerifiedAt()).isEqualTo(T0);

		expectInvalidToken(post("/api/auth/verify-email", "{\"token\":\"%s\"}".formatted(token)));
	}

	@Test
	void verificationLinkExpiresAfter48Hours() throws Exception {
		String token = mailer.lastTokenFor(EMAIL);
		clock.advance(Duration.ofHours(48).plusSeconds(1));
		expectInvalidToken(post("/api/auth/verify-email", "{\"token\":\"%s\"}".formatted(token)));
		assertThat(reloadUser().getEmailVerifiedAt()).isNull();
	}

	@Test
	void requestingANewLinkInvalidatesTheOldOne() throws Exception {
		String oldToken = mailer.lastTokenFor(EMAIL);

		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.post("/api/auth/verify-email/request")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokens.issue(user).value()))
			.andExpect(status().isAccepted());
		String newToken = mailer.lastTokenFor(EMAIL);

		assertThat(newToken).isNotEqualTo(oldToken);
		expectInvalidToken(post("/api/auth/verify-email", "{\"token\":\"%s\"}".formatted(oldToken)));
		post("/api/auth/verify-email", "{\"token\":\"%s\"}".formatted(newToken)).andExpect(status().isNoContent());
	}

	@Test
	void verifiedUsersGetNoNewLink() throws Exception {
		post("/api/auth/verify-email", "{\"token\":\"%s\"}".formatted(mailer.lastTokenFor(EMAIL)));
		mailer.clear();

		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.post("/api/auth/verify-email/request")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokens.issue(user).value()))
			.andExpect(status().isAccepted());
		assertThat(mailer.sentTo(EMAIL)).isEmpty();
	}

	@Test
	void requestingAVerificationLinkNeedsLogin() throws Exception {
		post("/api/auth/verify-email/request", "").andExpect(status().isUnauthorized());
	}

	// ---- password reset -------------------------------------------------------------------

	@Test
	void resetRequestLooksTheSameForUnknownEmails() throws Exception {
		mailer.clear();
		post("/api/auth/password-reset/request", "{\"email\":\"nobody@example.de\"}").andExpect(status().isAccepted());
		post("/api/auth/password-reset/request", "{\"email\":\"FLOW@example.de\"}").andExpect(status().isAccepted());

		assertThat(mailer.sentTo("nobody@example.de")).isEmpty();
		assertThat(mailer.lastTo(EMAIL).body()).contains("http://localhost:5173/reset-password?token=");
	}

	@Test
	void resetChangesThePasswordAndEndsAllSessions() throws Exception {
		String oldSession = refreshCookie(login(PASSWORD).andExpect(status().isOk()));
		post("/api/auth/password-reset/request", "{\"email\":\"%s\"}".formatted(EMAIL));
		String token = mailer.lastTokenFor(EMAIL);

		post("/api/auth/password-reset",
				"{\"token\":\"%s\",\"newPassword\":\"a-brand-new-passphrase\"}".formatted(token))
			.andExpect(status().isNoContent());

		login(PASSWORD).andExpect(status().isUnauthorized());
		login("a-brand-new-passphrase").andExpect(status().isOk());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/refresh")
			.cookie(new Cookie("lf_refresh", oldSession))).andExpect(status().isUnauthorized());
		expectInvalidToken(post("/api/auth/password-reset",
				"{\"token\":\"%s\",\"newPassword\":\"yet-another-passphrase\"}".formatted(token)));
	}

	@Test
	void weakNewPasswordIsRejectedWithoutBurningTheLink() throws Exception {
		post("/api/auth/password-reset/request", "{\"email\":\"%s\"}".formatted(EMAIL));
		String token = mailer.lastTokenFor(EMAIL);

		post("/api/auth/password-reset", "{\"token\":\"%s\",\"newPassword\":\"short\"}".formatted(token))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		post("/api/auth/password-reset",
				"{\"token\":\"%s\",\"newPassword\":\"a-brand-new-passphrase\"}".formatted(token))
			.andExpect(status().isNoContent());
	}

	@Test
	void resetLinkExpiresAfterOneHour() throws Exception {
		post("/api/auth/password-reset/request", "{\"email\":\"%s\"}".formatted(EMAIL));
		String token = mailer.lastTokenFor(EMAIL);
		clock.advance(Duration.ofMinutes(61));
		expectInvalidToken(post("/api/auth/password-reset",
				"{\"token\":\"%s\",\"newPassword\":\"a-brand-new-passphrase\"}".formatted(token)));
	}

	@Test
	void verificationTokenCannotResetThePassword() throws Exception {
		String verificationToken = mailer.lastTokenFor(EMAIL);
		expectInvalidToken(post("/api/auth/password-reset",
				"{\"token\":\"%s\",\"newPassword\":\"a-brand-new-passphrase\"}".formatted(verificationToken)));
	}

	@Test
	void disabledAccountsGetNoResetLink() throws Exception {
		User loaded = reloadUser();
		loaded.disable();
		users.save(loaded);
		mailer.clear();

		post("/api/auth/password-reset/request", "{\"email\":\"%s\"}".formatted(EMAIL)).andExpect(status().isAccepted());
		assertThat(mailer.sentTo(EMAIL)).isEmpty();
	}

	// ---- helpers --------------------------------------------------------------------------

	private ResultActions post(String path, String json) throws Exception {
		return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private ResultActions login(String password) throws Exception {
		return post("/api/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(EMAIL, password));
	}

	private static String refreshCookie(ResultActions result) {
		String header = result.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
		return header.substring("lf_refresh=".length(), header.indexOf(';'));
	}

	private void expectInvalidToken(ResultActions result) throws Exception {
		result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
	}

	private User reloadUser() {
		return users.findById(user.getId()).orElseThrow();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Config {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(T0);
		}

		@Bean
		@Primary
		RecordingMailer recordingMailer() {
			return new RecordingMailer();
		}

	}

}
