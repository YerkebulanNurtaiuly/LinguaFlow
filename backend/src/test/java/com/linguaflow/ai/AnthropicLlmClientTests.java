package com.linguaflow.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.linguaflow.ai.LlmOptions.Effort;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real client against a local stand-in for the Claude API: what it sends and how it
 * reads answers and errors. No network, no cost.
 */
class AnthropicLlmClientTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final LlmOptions OPTIONS = new LlmOptions(AiUseCase.VOCABULARY_ENRICHMENT, "claude-opus-5-5",
			Effort.LOW, 16_000, Duration.ofSeconds(5));

	private HttpServer server;

	private final AtomicReference<Exchange> last = new AtomicReference<>();

	private volatile int status = 200;

	private volatile String responseBody = message("end_turn", "{\"translation\":\"dog\"}");

	private volatile Duration delay = Duration.ZERO;

	private AnthropicLlmClient client;

	record Exchange(String path, Map<String, List<String>> headers, JsonNode body) {
	}

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
		client = new AnthropicLlmClient(AnthropicOkHttpClient.builder()
			.apiKey("sk-test")
			.baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
			.maxRetries(0)
			.build(), Clock.systemUTC());
	}

	@AfterEach
	void stopServer() {
		client.close();
		server.stop(0);
	}

	private void handle(HttpExchange exchange) throws IOException {
		String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		headers.putAll(exchange.getRequestHeaders());
		last.set(new Exchange(exchange.getRequestURI().getPath(), headers,
				request.isEmpty() ? null : JSON.readTree(request)));
		try {
			Thread.sleep(delay.toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static String message(String stopReason, String text) {
		return """
				{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
				 "content":[{"type":"text","text":%s}],
				 "stop_reason":"%s","stop_sequence":null,
				 "usage":{"input_tokens":120,"output_tokens":40,"cache_read_input_tokens":100,
				          "cache_creation_input_tokens":0}}
				""".formatted(JSON.writeValueAsString(text), stopReason);
	}

	private static String error(String type) {
		return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"nope\"}}";
	}

	@Test
	void sendsTheRequestTheWayTheUseCaseNeeds() {
		Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("translation", Map.of("type", "string")),
				"required", List.of("translation"), "additionalProperties", false);

		client.complete(new LlmPrompt("You enrich German words.", "Hund"), schema, OPTIONS);

		Exchange sent = last.get();
		assertThat(sent.path()).isEqualTo("/v1/messages");
		JsonNode body = sent.body();
		assertThat(body.get("model").asString()).isEqualTo("claude-opus-5-5");
		assertThat(body.get("max_tokens").asLong()).isEqualTo(16_000);
		assertThat(body.at("/messages/0/role").asString()).isEqualTo("user");
		assertThat(body.at("/messages/0/content").toString()).contains("Hund");
		// The template is cached across calls
		assertThat(body.at("/system/0/text").asString()).isEqualTo("You enrich German words.");
		assertThat(body.at("/system/0/cache_control/type").asString()).isEqualTo("ephemeral");
		assertThat(body.at("/output_config/effort").asString()).isEqualTo("low");
		assertThat(body.at("/output_config/format/type").asString()).isEqualTo("json_schema");
		assertThat(body.at("/output_config/format/schema/required/0").asString()).isEqualTo("translation");
		// Current models take neither a temperature nor a thinking budget
		assertThat(body.has("temperature")).isFalse();
		assertThat(body.has("thinking")).isFalse();
		// Refusals fall back to another model server-side
		assertThat(body.get("fallbacks").asString()).isEqualTo("default");
		assertThat(String.join(",", sent.headers().getOrDefault("Anthropic-beta", List.of())))
			.contains(AnthropicLlmClient.FALLBACK_BETA);
		// The key goes as x-api-key or a bearer token, depending on the SDK version
		assertThat(String.valueOf(sent.headers().get("X-api-key")) + sent.headers().get("Authorization"))
			.contains("sk-test");
	}

	@Test
	void leavesOutEmptySystemPromptsAndTheFormatWithoutSchema() {
		client.complete(new LlmPrompt("", "Hund"), null, OPTIONS);
		JsonNode body = last.get().body();
		assertThat(body.has("system")).isFalse();
		assertThat(body.at("/output_config/format").isMissingNode()).isTrue();
		assertThat(body.at("/output_config/effort").asString()).isEqualTo("low");
	}

	@Test
	void readsTheAnswerAndItsUsage() {
		LlmResponse response = client.complete(new LlmPrompt("sys", "Hund"), null, OPTIONS);
		assertThat(response.text()).isEqualTo("{\"translation\":\"dog\"}");
		assertThat(response.model()).isEqualTo("claude-opus-5-5");
		assertThat(response.inputTokens()).isEqualTo(120);
		assertThat(response.outputTokens()).isEqualTo(40);
		assertThat(response.cacheReadTokens()).isEqualTo(100);
		assertThat(response.latency().isNegative()).isFalse();
	}

	@Test
	void aRefusalThatSurvivesTheFallbackIsReported() {
		responseBody = message("refusal", "");
		assertThatThrownBy(() -> client.complete(new LlmPrompt("sys", "Hund"), null, OPTIONS))
			.isInstanceOfSatisfying(LlmException.class, ex -> {
				assertThat(ex.kind()).isEqualTo(LlmException.Kind.REFUSED);
				assertThat(ex.kind().retryable()).isFalse();
			});
	}

	@Test
	void aTruncatedAnswerIsReported() {
		responseBody = message("max_tokens", "{\"transl");
		assertThatThrownBy(() -> client.complete(new LlmPrompt("sys", "Hund"), null, OPTIONS))
			.isInstanceOfSatisfying(LlmException.class,
					ex -> assertThat(ex.kind()).isEqualTo(LlmException.Kind.TRUNCATED));
	}

	@ParameterizedTest
	@CsvSource({ "401, authentication_error, AUTHENTICATION, false", "403, permission_error, AUTHENTICATION, false",
			"400, invalid_request_error, INVALID_REQUEST, false", "404, not_found_error, INVALID_REQUEST, false",
			"429, rate_limit_error, RATE_LIMITED, true", "500, api_error, UNAVAILABLE, true",
			"529, overloaded_error, UNAVAILABLE, true" })
	void translatesProviderErrors(int code, String type, LlmException.Kind kind, boolean retryable) {
		status = code;
		responseBody = error(type);
		assertThatThrownBy(() -> client.complete(new LlmPrompt("sys", "Hund"), null, OPTIONS))
			.isInstanceOfSatisfying(LlmException.class, ex -> {
				assertThat(ex.kind()).isEqualTo(kind);
				assertThat(ex.kind().retryable()).isEqualTo(retryable);
				// The API key never ends up in messages
				assertThat(ex.getMessage()).doesNotContain("sk-test");
			});
	}

	@Test
	void stopsWaitingAfterTheUseCaseTimeout() {
		delay = Duration.ofSeconds(2);
		LlmOptions quick = new LlmOptions(AiUseCase.ANSWER_EVALUATION, "claude-opus-5-5", Effort.LOW, 1_000,
				Duration.ofMillis(300));
		assertThatThrownBy(() -> client.complete(new LlmPrompt("sys", "Hund"), null, quick))
			.isInstanceOfSatisfying(LlmException.class, ex -> {
				assertThat(ex.kind()).isEqualTo(LlmException.Kind.TIMEOUT);
				assertThat(ex.kind().retryable()).isTrue();
			});
	}

}
