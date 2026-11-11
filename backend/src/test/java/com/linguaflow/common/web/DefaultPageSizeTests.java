package com.linguaflow.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.testsupport.TestUsers;
import com.linguaflow.testsupport.TestUsers.TestUser;
import com.linguaflow.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Paged lists default to the configured 20 items (spring.data.web.pageable). Spring's
 * {@code @PageableDefault} would quietly set its own default of 10, so it isn't used.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DefaultPageSizeTests {

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

	@ParameterizedTest
	@ValueSource(strings = { "/api/vocabulary", "/api/grammar-topics", "/api/study-sessions", "/api/ai/jobs",
			"/api/flashcards" })
	void listsDefaultToTwentyPerPage(String path) throws Exception {
		users.deleteAll();
		TestUser learner = new TestUsers(registration, accessTokens, jdbc).learner("pages@example.de");

		mvc.perform(get(path).header("Authorization", learner.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.size").value(20));
	}

	@Test
	void noControllerSetsItsOwnDefaultSize() throws IOException {
		try (Stream<Path> sources = Files.walk(Path.of("src", "main", "java"))) {
			List<Path> offenders = sources.filter(path -> path.toString().endsWith(".java"))
				.filter(path -> read(path).contains("@PageableDefault"))
				.toList();
			assertThat(offenders).as("use @SortDefault alone; @PageableDefault overrides the size of 20").isEmpty();
		}
	}

	private static String read(Path path) {
		try {
			// The comment in AiJobController explains the rule; only real annotations count
			return Files.readString(path).replace("No @PageableDefault", "");
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
