package com.linguaflow.ai;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.linguaflow.ai.job.AiJobWorker;
import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationRequest;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.testsupport.IntegrationTest;
import com.linguaflow.testsupport.TestData;
import com.linguaflow.testsupport.TestData.ExerciseSetIds;
import com.linguaflow.testsupport.TestUsers;
import com.linguaflow.user.User;
import com.linguaflow.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SEC-02: the AI only ever gets learning content. A learner with a distinctive name,
 * email address and private notes uses every AI feature, and none of those details may
 * appear in any prompt sent to the model.
 */
@IntegrationTest
class PersonalDataInPromptsTests {

	private static final String NAME = "Zebulon Quixotic";

	private static final String EMAIL = "zebulon.quixotic@example.de";

	private static final String PRIVATE_NOTE = "PRIVATE-NOTE-7731 my phone is +49 151 23456789";

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

	@Autowired
	private AiJobWorker worker;

	private UUID userId;

	private String bearer;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		jdbc.update("delete from ai_jobs");
		User user = registration.register(new RegistrationRequest(EMAIL, "correct-horse-battery", NAME));
		TestUsers.addGerman(jdbc, user.getId());
		userId = user.getId();
		bearer = "Bearer " + accessTokens.issue(user).value();
	}

	private ResultActions send(String uri, String body) throws Exception {
		return mvc.perform(post(uri).header("Authorization", bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	@Test
	void noAiCallCarriesTheLearnersPersonalData(FakeLlmClient fake) throws Exception {
		TestData data = new TestData(jdbc);
		UUID dativ = data.topic(userId, "Dativ").status("LEARNING").insert();
		jdbc.update("update grammar_topics set personal_notes = ? where id = ?", PRIVATE_NOTE, dativ);
		jdbc.update("update study_sessions set notes = ? where user_id = ?", PRIVATE_NOTE, userId);

		// Background AI: enrichment, an explanation, exercise generation
		send("/api/vocabulary", "{\"lemma\": \"Hund\", \"notes\": \"" + PRIVATE_NOTE + "\"}");
		send("/api/grammar-topics/" + dativ + "/explanation", "{}");
		send("/api/exercise-sets",
				"{\"grammarTopicIds\": [\"" + dativ + "\"], \"types\": [\"FILL_BLANK\"], \"count\": 2}");
		worker.runDueJobs();

		// While the learner waits: grading an open answer, explaining a mistake
		ExerciseSetIds set = data.exerciseSet(userId)
			.with(TestData.translation("I give the dog water.", "Ich gebe dem Hund Wasser.").topics(dativ),
					TestData.fillBlank("Ich gebe ___ Hund Wasser.", "dem").topics(dativ))
			.insert();
		send("/api/exercises/" + set.exercise(0) + "/attempts", "{\"text\": \"Ich gebe den Hund Wasser.\"}");
		send("/api/exercises/" + set.exercise(1) + "/attempts", "{\"blanks\": [\"den\"]}");
		UUID attempt = jdbc.queryForObject("select id from exercise_attempts where exercise_id = ?", UUID.class,
				set.exercise(1));
		send("/api/exercises/" + set.exercise(1) + "/attempts/" + attempt + "/explanation", "{}");

		Set<AiUseCase> used = fake.calls()
			.stream()
			.map(call -> call.options().useCase())
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(AiUseCase.class)));
		assertThat(used).as("every AI feature was exercised").containsExactlyInAnyOrder(AiUseCase.values());
		for (FakeLlmClient.Call call : fake.calls()) {
			String prompt = call.prompt().system() + "\n" + call.prompt().user();
			assertThat(prompt).as("%s prompt", call.options().useCase())
				.doesNotContainIgnoringCase("Zebulon")
				.doesNotContainIgnoringCase("Quixotic")
				.doesNotContainIgnoringCase(EMAIL)
				.doesNotContain(userId.toString())
				.doesNotContain("PRIVATE-NOTE-7731")
				.doesNotContain("23456789");
		}
	}

}
