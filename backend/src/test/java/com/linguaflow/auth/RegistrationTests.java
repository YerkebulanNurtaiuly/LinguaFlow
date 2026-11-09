package com.linguaflow.auth;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.user.User;
import com.linguaflow.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/auth/register end to end: no token, no CSRF token, real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class RegistrationTests {

	private static final String VALID_PASSWORD = "correct-horse-battery";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@BeforeEach
	void cleanUp() {
		users.deleteAll();
	}

	@Test
	void createsAccountWithHashedPassword() throws Exception {
		register("Anna@Example.de", VALID_PASSWORD, " Anna ")
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", "/api/users/me"))
			.andExpect(jsonPath("$.id").isNotEmpty())
			.andExpect(jsonPath("$.email").value("Anna@Example.de"))
			.andExpect(jsonPath("$.displayName").value("Anna"))
			.andExpect(jsonPath("$.createdAt").isNotEmpty())
			.andExpect(content().string(not(containsString("password"))))
			.andExpect(content().string(not(containsString(VALID_PASSWORD))));

		User user = users.findByEmail("anna@example.de").orElseThrow();
		assertThat(user.getPasswordHash()).startsWith("{bcrypt}").doesNotContain(VALID_PASSWORD);
		assertThat(passwordEncoder.matches(VALID_PASSWORD, user.getPasswordHash())).isTrue();
	}

	@Test
	void duplicateEmailIgnoringCaseIs409() throws Exception {
		register("ben@example.de", VALID_PASSWORD, "Ben").andExpect(status().isCreated());

		register("BEN@Example.DE", VALID_PASSWORD, "Ben again")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("EMAIL_TAKEN"))
			.andExpect(jsonPath("$.detail").value("An account with this email already exists"));
		assertThat(users.count()).isEqualTo(1);
	}

	@ParameterizedTest(name = "{3}")
	@CsvSource(delimiter = '|', textBlock = """
			not-an-email      | correct-horse-battery | Anna | email      | invalid email
			''                | correct-horse-battery | Anna | email      | blank email
			anna@example.de   | short                 | Anna | password   | password too short
			anna@example.de   | password123           | Anna | password   | common password
			anna@example.de   | '          '          | Anna | password   | whitespace password
			anna@example.de   | correct-horse-battery | ''   | displayName | blank display name
			""")
	void rejectsInvalidInput(String email, String password, String displayName, String field, String caseName)
			throws Exception {
		register(email, password, displayName)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[?(@.field == '" + field + "')]").exists());
		assertThat(users.count()).isZero();
	}

	@Test
	void rejectsPasswordLongerThan72Bytes() throws Exception {
		String fortyUmlauts = "ä".repeat(40); // 40 characters but 80 UTF-8 bytes
		register("long@example.de", fortyUmlauts, "Long")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("password"))
			.andExpect(jsonPath("$.errors[0].message").value(containsString("72 bytes")));
	}

	@Test
	void missingBodyIsMalformed() throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	private ResultActions register(String email, String password, String displayName) throws Exception {
		String body = """
				{"email": %s, "password": %s, "displayName": %s}
				""".formatted(json(email), json(password), json(displayName));
		return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static String json(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

}
