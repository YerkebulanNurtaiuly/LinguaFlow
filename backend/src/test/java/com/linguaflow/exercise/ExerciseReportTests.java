package com.linguaflow.exercise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.testsupport.OwnershipAssertions;
import com.linguaflow.testsupport.TestUsers;
import com.linguaflow.testsupport.TestUsers.TestUser;
import com.linguaflow.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Reporting bad exercises (EX-07). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ExerciseReportTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ExerciseSetRepository sets;

	@Autowired
	private ExerciseRepository exercises;

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

	private ExerciseSet set;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		TestUsers testUsers = new TestUsers(registration, accessTokens, jdbc);
		anna = testUsers.learner("anna@example.de");
		ben = testUsers.learner("ben@example.de");
		set = sets.saveAndFlush(ExerciseSetRepositoryTests.newSet(anna.id(), 2));
	}

	private UUID exercise(ExerciseType type, int position) {
		return exercises.saveAndFlush(new Exercise(set, position, ExerciseSamples.of(type), ExerciseDifficulty.MEDIUM,
				List.of(), List.of(), null))
			.getId();
	}

	private ResultActions report(TestUser user, UUID id, String body) throws Exception {
		return mvc.perform(post("/api/exercises/{id}/report", id).header("Authorization", user.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	@Test
	void aReportFlagsTheExerciseAndLeavesItOutOfPractice() throws Exception {
		UUID bad = exercise(ExerciseType.MULTIPLE_CHOICE, 0);
		UUID good = exercise(ExerciseType.FILL_BLANK, 1);

		report(anna, bad, "{\"reason\":\"  Two options are correct. \"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(bad.toString()))
			.andExpect(jsonPath("$.reported").value(true));

		assertThat(jdbc.queryForObject("select report_reason from exercises where id = ?", String.class, bad))
			.isEqualTo("Two options are correct.");
		mvc.perform(get("/api/exercise-sets/{id}", set.getId()).header("Authorization", anna.bearer()))
			.andExpect(jsonPath("$.exercises[0].reported").value(true))
			.andExpect(jsonPath("$.exercises[1].reported").value(false));
		assertThat(exercises.findAllByUserIdAndExerciseSetIdAndReportedAtIsNullOrderByPosition(anna.id(), set.getId()))
			.extracting(Exercise::getId)
			.containsExactly(good);

		// Reporting again keeps the first time and takes the new reason; no body is fine too
		Instant first = jdbc.queryForObject("select reported_at from exercises where id = ?", Instant.class, bad);
		report(anna, bad, "{\"reason\":\"Option 2 is also right\"}").andExpect(status().isOk());
		assertThat(jdbc.queryForObject("select reported_at from exercises where id = ?", Instant.class, bad))
			.isEqualTo(first);
		assertThat(jdbc.queryForObject("select report_reason from exercises where id = ?", String.class, bad))
			.isEqualTo("Option 2 is also right");
		mvc.perform(post("/api/exercises/{id}/report", good).header("Authorization", anna.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reported").value(true));
	}

	@Test
	void aReportCanBeTakenBack() throws Exception {
		UUID id = exercise(ExerciseType.WORD_ORDER, 0);
		report(anna, id, "{}").andExpect(status().isOk());

		mvc.perform(delete("/api/exercises/{id}/report", id).header("Authorization", anna.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reported").value(false));
		assertThat(jdbc.queryForObject("select reported_at is null and report_reason is null from exercises where id = ?",
				Boolean.class, id))
			.isTrue();
	}

	@Test
	void reasonsAreLimitedAndOnlyTheOwnerCanReport() throws Exception {
		UUID id = exercise(ExerciseType.TRANSLATION, 0);
		report(anna, id, "{\"reason\":\"" + "x".repeat(Exercise.MAX_REPORT_REASON + 1) + "\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("reason"));

		OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.POST, "/api/exercises/{id}/report", id,
				anna.accessToken(), ben.accessToken(), "{}");
		OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.DELETE, "/api/exercises/{id}/report", id,
				anna.accessToken(), ben.accessToken());
	}

	@Test
	void adminsSeeEveryReportWithItsSolution() throws Exception {
		UUID choice = exercise(ExerciseType.MULTIPLE_CHOICE, 0);
		UUID order = exercise(ExerciseType.WORD_ORDER, 1);
		exercise(ExerciseType.FILL_BLANK, 2);
		report(anna, choice, "{\"reason\":\"Two options fit\"}").andExpect(status().isOk());
		report(anna, order, "{}").andExpect(status().isOk());

		mvc.perform(get("/api/admin/exercise-reports").header("Authorization", anna.bearer()))
			.andExpect(status().isForbidden());

		String admin = adminBearer();
		mvc.perform(get("/api/admin/exercise-reports").header("Authorization", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.content[0].id").value(order.toString()))
			.andExpect(jsonPath("$.content[0].solution.tokens[0]").value("Ich"))
			.andExpect(jsonPath("$.content[1].languageCode").value("de"))
			.andExpect(jsonPath("$.content[1].prompt.question").value("Ich helfe ___ Frau."))
			.andExpect(jsonPath("$.content[1].solution.correctIndex").value(1))
			.andExpect(jsonPath("$.content[1].reportReason").value("Two options fit"))
			.andExpect(jsonPath("$.content[1].userId").doesNotExist());
		mvc.perform(get("/api/admin/exercise-reports?type=MULTIPLE_CHOICE").header("Authorization", admin))
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[0].type").value("MULTIPLE_CHOICE"));
	}

	private String adminBearer() {
		jdbc.update("update users set role = 'ADMIN' where id = ?", ben.id());
		return "Bearer " + accessTokens.issue(users.findById(ben.id()).orElseThrow()).value();
	}

}
