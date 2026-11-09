package com.linguaflow.testsupport;

import java.util.UUID;

import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationRequest;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.user.User;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creates learners for full-context tests: a registered user with an active German
 * profile (needed by every learning table's FK) and a valid access token.
 * <pre>{@code
 * TestUsers users = new TestUsers(registration, accessTokens, jdbc);
 * TestUser anna = users.learner("anna@example.de");
 * mvc.perform(get(...).header(AUTHORIZATION, anna.bearer()));
 * }</pre>
 */
public class TestUsers {

	private final RegistrationService registration;

	private final AccessTokenService accessTokens;

	private final JdbcTemplate jdbc;

	public TestUsers(RegistrationService registration, AccessTokenService accessTokens, JdbcTemplate jdbc) {
		this.registration = registration;
		this.accessTokens = accessTokens;
		this.jdbc = jdbc;
	}

	public TestUser learner(String email) {
		TestUser user = user(email);
		addGerman(jdbc, user.id());
		return user;
	}

	/** A registered user with a token but no learning language yet (e.g. before onboarding). */
	public TestUser user(String email) {
		User user = registration.register(new RegistrationRequest(email, "correct-horse-battery", "Test Learner"));
		return new TestUser(user.getId(), accessTokens.issue(user).value());
	}

	/** Gives an existing user an active German (target) / English (explanation) profile. */
	public static void addGerman(JdbcTemplate jdbc, UUID userId) {
		jdbc.update("insert into user_languages (user_id, language_code, explanation_language_code, active) "
				+ "values (?, 'de', 'en', true)", userId);
	}

	/** Inserts a bare user row (for repository tests without the auth stack) and returns its ID. */
	public static UUID insertLearner(JdbcTemplate jdbc) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into users (id, email, password_hash, display_name) values (?, ?, '{noop}x', 'Test')", id,
				id + "@example.de");
		addGerman(jdbc, id);
		return id;
	}

	public record TestUser(UUID id, String accessToken) {

		public String bearer() {
			return "Bearer " + accessToken;
		}

	}

}
