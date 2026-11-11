package com.linguaflow.ai.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.ai.AiCaller;
import com.linguaflow.ai.AiErrors;
import com.linguaflow.ai.FakeLlmClient;
import com.linguaflow.ai.LlmClient;
import com.linguaflow.ai.LlmException;
import com.linguaflow.ai.OutputCheck;
import com.linguaflow.ai.StructuredLlm;
import com.linguaflow.ai.prompt.PromptLibrary;
import com.linguaflow.ai.prompt.PromptTemplate;
import com.linguaflow.ai.prompt.PromptVar;
import com.linguaflow.ai.prompt.PromptVariables;
import com.linguaflow.ai.prompt.RenderedPrompt;
import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ErrorCode;
import com.linguaflow.common.error.RetryLaterException;
import com.linguaflow.testsupport.TestUsers;
import com.linguaflow.testsupport.TestUsers.TestUser;
import com.linguaflow.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The usage log, the daily quota and the usage endpoints against a real database (AI-05). */
@SpringBootTest(properties = { "linguaflow.ai.quota.daily-requests=3" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AiUsageApiTests {

	record Word(String translation) {
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StructuredLlm llm;

	@Autowired
	private PromptLibrary prompts;

	@Autowired
	private LlmClient client;

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

	private FakeLlmClient fake() {
		return (FakeLlmClient) client;
	}

	@BeforeEach
	void setUp() {
		users.deleteAll();
		jdbc.update("delete from ai_requests");
		fake().reset();
		TestUsers testUsers = new TestUsers(registration, accessTokens, jdbc);
		anna = testUsers.learner("anna@example.de");
		ben = testUsers.learner("ben@example.de");
	}

	@AfterEach
	void tearDown() {
		fake().reset();
	}

	private RenderedPrompt hund() {
		return prompts.render(PromptTemplate.VOCABULARY_ENRICHMENT,
				PromptVariables.forLanguages("de", "en").set(PromptVar.WORD, "Hund"));
	}

	private void enrich(TestUser user) {
		llm.complete(AiCaller.user(user.id()), hund(), Word.class, OutputCheck.none());
	}

	@Test
	void logsEveryCallWithoutItsContent() {
		UUID jobId = null;
		llm.complete(new AiCaller(anna.id(), jobId), hund(), Word.class, OutputCheck.none());

		Map<String, Object> row = jdbc.queryForMap("select * from ai_requests");
		assertThat(row).containsEntry("user_id", anna.id())
			.containsEntry("use_case", "VOCABULARY_ENRICHMENT")
			.containsEntry("model", "fake-claude-opus-5-5")
			.containsEntry("prompt_version", "vocabulary-enrichment/v1")
			.containsEntry("success", true);
		assertThat((Integer) row.get("input_tokens")).isPositive();
		assertThat((Integer) row.get("output_tokens")).isPositive();
		assertThat(row.get("cost_estimate_usd").toString()).isEqualTo("0.000000");
		assertThat(row.get("error_code")).isNull();
		// Neither the prompt nor the answer is stored anywhere in the row
		assertThat(row.values()).noneMatch(value -> String.valueOf(value).contains("Hund"));
	}

	@Test
	void logsFailedCallsWithTheirKind() {
		fake().failNext(new LlmException(LlmException.Kind.UNAVAILABLE, "overloaded"));

		assertThatThrownBy(() -> enrich(anna)).isInstanceOf(LlmException.class);

		Map<String, Object> row = jdbc.queryForMap("select * from ai_requests");
		assertThat(row).containsEntry("success", false)
			.containsEntry("error_code", "UNAVAILABLE")
			.containsEntry("model", "claude-opus-5-5");
		assertThat(row.get("input_tokens")).isNull();
		assertThat(row.get("latency_ms")).isNotNull();
	}

	@Test
	void stopsAUserAtTheDailyLimit() throws Exception {
		enrich(anna);
		enrich(anna);
		enrich(anna);

		assertThatThrownBy(() -> enrich(anna)).isInstanceOfSatisfying(AiQuotaExceededException.class, ex -> {
			assertThat(ex.kind()).isEqualTo(LlmException.Kind.QUOTA_EXCEEDED);
			assertThat(ex.resetsAt())
				.isEqualTo(LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
		});
		// The refused call never reached the provider and isn't logged
		assertThat(fake().calls()).hasSize(3);
		assertThat(jdbc.queryForObject("select count(*) from ai_requests", Integer.class)).isEqualTo(3);

		// Other users and system work aren't affected
		enrich(ben);
		llm.complete(AiCaller.system(), hund(), Word.class, OutputCheck.none());

		mvc.perform(get("/api/ai/usage").header("Authorization", anna.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.requestsToday").value(3))
			.andExpect(jsonPath("$.requestLimit").value(3))
			.andExpect(jsonPath("$.tokenLimit").value(1_500_000))
			.andExpect(jsonPath("$.limited").value(true))
			.andExpect(jsonPath("$.exceeded").value(true))
			.andExpect(jsonPath("$.resetsAt").exists());
		mvc.perform(get("/api/ai/usage").header("Authorization", ben.bearer()))
			.andExpect(jsonPath("$.requestsToday").value(1))
			.andExpect(jsonPath("$.exceeded").value(false));
	}

	@Test
	void synchronousCallersGetA429WithRetryAfterOrA503() {
		Instant resetsAt = Instant.parse("2026-11-12T00:00:00Z");
		Clock clock = Clock.fixed(Instant.parse("2026-11-11T23:00:00Z"), ZoneOffset.UTC);

		ApiException quota = AiErrors.toApiException(new AiQuotaExceededException("limit", resetsAt), clock);
		assertThat(quota).isInstanceOfSatisfying(RetryLaterException.class, ex -> {
			assertThat(ex.code()).isEqualTo(ErrorCode.AI_QUOTA_EXCEEDED);
			assertThat(ex.retryAfterSeconds()).isEqualTo(3600);
			assertThat(ex.properties()).containsEntry("resetsAt", "2026-11-12T00:00:00Z");
		});
		ApiException down = AiErrors.toApiException(new LlmException(LlmException.Kind.TIMEOUT, "secret detail"), clock);
		assertThat(down.code()).isEqualTo(ErrorCode.AI_UNAVAILABLE);
		assertThat(down.getMessage()).doesNotContain("secret detail");
	}

	private void insertRequest(UUID userId, String useCase, String model, String day, int input, int output,
			String cost, boolean success) {
		jdbc.update("""
				insert into ai_requests (id, user_id, use_case, model, prompt_version, input_tokens, output_tokens,
				    latency_ms, cost_estimate_usd, success, error_code, created_at)
				values (?, ?, ?, ?, 'v', ?, ?, 100, ?::numeric, ?, ?, ?::timestamptz)""", UUID.randomUUID(), userId,
				useCase, model, success ? input : null, success ? output : null, success ? cost : null, success,
				success ? null : "TIMEOUT", day + "T12:00:00Z");
	}

	private String adminBearer() {
		jdbc.update("update users set role = 'ADMIN' where id = ?", ben.id());
		return "Bearer " + accessTokens.issue(users.findById(ben.id()).orElseThrow()).value();
	}

	@Test
	void adminsSeeTheSummaryAcrossUsers() throws Exception {
		insertRequest(anna.id(), "VOCABULARY_ENRICHMENT", "claude-opus-5-5", "2026-11-01", 1000, 200, "0.008000", true);
		insertRequest(anna.id(), "VOCABULARY_ENRICHMENT", "claude-opus-5-5", "2026-11-02", 1000, 200, "0.008000", true);
		insertRequest(ben.id(), "GRAMMAR_EXPLANATION", "claude-sonnet-5-5", "2026-11-02", 500, 1000, "0.011000", true);
		insertRequest(ben.id(), "GRAMMAR_EXPLANATION", "claude-sonnet-5-5", "2026-11-02", 0, 0, null, false);
		// Outside the range
		insertRequest(anna.id(), "VOCABULARY_ENRICHMENT", "claude-opus-5-5", "2026-10-31", 9999, 9999, "9.000000", true);

		mvc.perform(get("/api/admin/ai-usage").param("from", "2026-11-01")
			.param("to", "2026-11-02")
			.header("Authorization", adminBearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.from").value("2026-11-01"))
			.andExpect(jsonPath("$.totals.requests").value(4))
			.andExpect(jsonPath("$.totals.failures").value(1))
			.andExpect(jsonPath("$.totals.inputTokens").value(2500))
			.andExpect(jsonPath("$.totals.outputTokens").value(1400))
			.andExpect(jsonPath("$.totals.costUsd").value(0.027))
			.andExpect(jsonPath("$.byUseCase[0].useCase").value("GRAMMAR_EXPLANATION"))
			.andExpect(jsonPath("$.byUseCase[0].totals.requests").value(2))
			.andExpect(jsonPath("$.byUseCase[1].totals.costUsd").value(0.016))
			.andExpect(jsonPath("$.byModel[1].model").value("claude-sonnet-5-5"))
			.andExpect(jsonPath("$.byDay[0].day").value("2026-11-01"))
			.andExpect(jsonPath("$.byDay[1].totals.requests").value(3))
			.andExpect(jsonPath("$.topUsers[0].userId").value(anna.id().toString()))
			.andExpect(jsonPath("$.topUsers[0].costUsd").value(0.016))
			.andExpect(jsonPath("$.topUsers[1].requests").value(2));
	}

	@Test
	void theSummaryIsForAdminsOnlyAndChecksItsRange() throws Exception {
		mvc.perform(get("/api/admin/ai-usage").header("Authorization", anna.bearer()))
			.andExpect(status().isForbidden());
		String admin = adminBearer();
		mvc.perform(get("/api/admin/ai-usage").header("Authorization", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.to").value(LocalDate.now(ZoneOffset.UTC).toString()))
			.andExpect(jsonPath("$.from").value(LocalDate.now(ZoneOffset.UTC).minusDays(29).toString()))
			.andExpect(jsonPath("$.totals.requests").value(0))
			.andExpect(jsonPath("$.byDay").isEmpty());
		mvc.perform(get("/api/admin/ai-usage").param("from", "2026-11-02")
			.param("to", "2026-11-01")
			.header("Authorization", admin))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("from"));
		mvc.perform(get("/api/admin/ai-usage").param("from", "2024-01-01")
			.param("to", "2026-11-01")
			.header("Authorization", admin))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/ai/usage")).andExpect(status().isUnauthorized());
	}

}
