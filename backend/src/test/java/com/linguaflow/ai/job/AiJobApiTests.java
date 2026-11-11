package com.linguaflow.ai.job;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The learner's view of their AI jobs (AI-08). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AiJobApiTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AiJobQueue queue;

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
		jdbc.update("delete from ai_jobs");
		TestUsers testUsers = new TestUsers(registration, accessTokens, jdbc);
		anna = testUsers.learner("anna@example.de");
		ben = testUsers.learner("ben@example.de");
	}

	private UUID job(TestUser owner, AiJobType type) {
		return queue.enqueue(type, owner.id(), Map.of("vocabularyItemIds", List.of("a"))).getId();
	}

	private void update(UUID job, String set, Object... args) {
		Object[] all = new Object[args.length + 1];
		System.arraycopy(args, 0, all, 0, args.length);
		all[args.length] = job;
		jdbc.update("update ai_jobs set " + set + " where id = ?", all);
	}

	private MockHttpServletRequestBuilder as(TestUser user, MockHttpServletRequestBuilder request) {
		return request.header("Authorization", user.bearer());
	}

	@Test
	void showsAQueuedJob() throws Exception {
		UUID id = job(anna, AiJobType.VOCABULARY_ENRICHMENT);

		mvc.perform(as(anna, get("/api/ai/jobs/{id}", id)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.type").value("VOCABULARY_ENRICHMENT"))
			.andExpect(jsonPath("$.status").value("QUEUED"))
			.andExpect(jsonPath("$.finished").value(false))
			.andExpect(jsonPath("$.attempts").value(0))
			.andExpect(jsonPath("$.maxAttempts").value(3))
			.andExpect(jsonPath("$.waiting").doesNotExist())
			.andExpect(jsonPath("$.nextRunAt").exists())
			.andExpect(jsonPath("$.result").doesNotExist());
	}

	@Test
	void saysWhyAQueuedJobIsWaitingButNotTheRawError() throws Exception {
		UUID retrying = job(anna, AiJobType.VOCABULARY_ENRICHMENT);
		update(retrying, "attempts = 1, last_error = ?", "UNAVAILABLE: 529 overloaded_error from api.example");
		UUID postponed = job(anna, AiJobType.VOCABULARY_ENRICHMENT);
		update(postponed, "last_error = ?", "Daily AI limit reached; it resets at 00:00 UTC");

		mvc.perform(as(anna, get("/api/ai/jobs/{id}", retrying)))
			.andExpect(jsonPath("$.waiting").value("RETRY"))
			.andExpect(jsonPath("$.attempts").value(1))
			.andExpect(content().string(not(containsString("overloaded"))));
		mvc.perform(as(anna, get("/api/ai/jobs/{id}", postponed))).andExpect(jsonPath("$.waiting").value("DAILY_LIMIT"));
	}

	@Test
	void showsTheResultOnceSucceededAndNothingWhileRunning() throws Exception {
		UUID done = job(anna, AiJobType.VOCABULARY_ENRICHMENT);
		update(done, "status = 'SUCCEEDED', attempts = 1, result = '{\"enriched\": 20}'::jsonb");
		UUID running = job(anna, AiJobType.VOCABULARY_ENRICHMENT);
		update(running, "status = 'RUNNING', attempts = 1, locked_by = 'w', locked_at = now()");
		UUID dead = job(anna, AiJobType.VOCABULARY_ENRICHMENT);
		update(dead, "status = 'DEAD', attempts = 3, last_error = 'TIMEOUT: slow'");

		mvc.perform(as(anna, get("/api/ai/jobs/{id}", done)))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.finished").value(true))
			.andExpect(jsonPath("$.result.enriched").value(20))
			.andExpect(jsonPath("$.nextRunAt").doesNotExist());
		mvc.perform(as(anna, get("/api/ai/jobs/{id}", running)))
			.andExpect(jsonPath("$.status").value("RUNNING"))
			.andExpect(jsonPath("$.finished").value(false))
			.andExpect(jsonPath("$.nextRunAt").doesNotExist())
			.andExpect(content().string(not(containsString("lockedBy"))));
		mvc.perform(as(anna, get("/api/ai/jobs/{id}", dead)))
			.andExpect(jsonPath("$.status").value("DEAD"))
			.andExpect(jsonPath("$.finished").value(true))
			.andExpect(jsonPath("$.waiting").doesNotExist())
			.andExpect(content().string(not(containsString("TIMEOUT"))));
	}

	@Test
	void onlyTheOwnerSeesAJob() throws Exception {
		UUID id = job(anna, AiJobType.VOCABULARY_ENRICHMENT);
		OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.GET, "/api/ai/jobs/{id}", id, anna.accessToken(),
				ben.accessToken());
		mvc.perform(get("/api/ai/jobs/{id}", id)).andExpect(status().isUnauthorized());
	}

	@Test
	void listsOwnJobsNewestFirstWithFilters() throws Exception {
		UUID first = job(anna, AiJobType.VOCABULARY_ENRICHMENT);
		UUID second = job(anna, AiJobType.GRAMMAR_EXPLANATION);
		UUID third = job(anna, AiJobType.VOCABULARY_ENRICHMENT);
		update(third, "status = 'FAILED', attempts = 1, last_error = 'x'");
		job(ben, AiJobType.VOCABULARY_ENRICHMENT);

		mvc.perform(as(anna, get("/api/ai/jobs")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.totalElements").value(3))
			.andExpect(jsonPath("$.content[0].id").value(third.toString()))
			.andExpect(jsonPath("$.content[2].id").value(first.toString()));
		mvc.perform(as(anna, get("/api/ai/jobs").param("type", "VOCABULARY_ENRICHMENT")))
			.andExpect(jsonPath("$.totalElements").value(2));
		mvc.perform(as(anna, get("/api/ai/jobs").param("status", "QUEUED").param("status", "RUNNING")))
			.andExpect(jsonPath("$.content[*].id").value(containsInAnyOrder(first.toString(), second.toString())));
		mvc.perform(as(anna, get("/api/ai/jobs").param("sort", "payload,asc")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("sort"));
	}

	@Test
	void reportsTheProgressOfABatch() throws Exception {
		List<UUID> batch = IntStream.range(0, 4).mapToObj(i -> job(anna, AiJobType.VOCABULARY_ENRICHMENT)).toList();
		update(batch.get(0), "status = 'SUCCEEDED', attempts = 1");
		update(batch.get(1), "status = 'RUNNING', attempts = 1, locked_by = 'w', locked_at = now()");
		update(batch.get(2), "status = 'FAILED', attempts = 1, last_error = 'x'");
		UUID bens = job(ben, AiJobType.VOCABULARY_ENRICHMENT);
		UUID missing = UUID.randomUUID();

		mvc.perform(as(anna, get("/api/ai/jobs/progress").param("id", batch.get(0).toString(), batch.get(1).toString(),
				batch.get(2).toString(), batch.get(3).toString(), bens.toString(), missing.toString(),
				batch.get(0).toString())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(4))
			.andExpect(jsonPath("$.queued").value(1))
			.andExpect(jsonPath("$.running").value(1))
			.andExpect(jsonPath("$.succeeded").value(1))
			.andExpect(jsonPath("$.failed").value(1))
			.andExpect(jsonPath("$.dead").value(0))
			.andExpect(jsonPath("$.finished").value(false))
			// Ben's job is indistinguishable from a missing one
			.andExpect(jsonPath("$.notFound").value(containsInAnyOrder(bens.toString(), missing.toString())));

		update(batch.get(1), "status = 'SUCCEEDED', locked_by = null, locked_at = null");
		update(batch.get(3), "status = 'DEAD', attempts = 3, last_error = 'x'");
		mvc.perform(as(anna, get("/api/ai/jobs/progress").param("id", batch.stream().map(UUID::toString).toArray(String[]::new))))
			.andExpect(jsonPath("$.finished").value(true))
			.andExpect(jsonPath("$.succeeded").value(2))
			.andExpect(jsonPath("$.dead").value(1))
			.andExpect(jsonPath("$.notFound").isEmpty());
	}

	@Test
	void progressNeedsBetweenOneAndAHundredJobs() throws Exception {
		mvc.perform(as(anna, get("/api/ai/jobs/progress")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("id"));
		String[] tooMany = IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID().toString()).toArray(String[]::new);
		mvc.perform(as(anna, get("/api/ai/jobs/progress").param("id", tooMany))).andExpect(status().isBadRequest());
		mvc.perform(as(anna, get("/api/ai/jobs/progress").param("id", "not-a-uuid"))).andExpect(status().isBadRequest());
	}

}
