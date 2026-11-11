package com.linguaflow.ai;

import java.io.InterruptedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.RequestOptions;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.errors.UnprocessableEntityException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import org.jspecify.annotations.Nullable;

/**
 * {@link LlmClient} on the Claude API through the official Anthropic Java SDK.
 * <ul>
 * <li>The system prompt is marked for prompt caching, so repeated templates (AI-03) are
 * billed at the cache-read rate.</li>
 * <li>Structured answers use {@code output_config.format} with the caller's JSON Schema.</li>
 * <li>Refusals fall back to another model server-side ({@code fallbacks: "default"});
 * a refusal that survives the fallback is {@link LlmException.Kind#REFUSED}.</li>
 * <li>Thinking is left at the model's adaptive default; effort controls the depth.</li>
 * </ul>
 * The SDK retries rate limits, overload and connection errors itself (twice, with
 * backoff) within the use case's timeout; AI-05 adds the circuit breaker on top.
 */
public class AnthropicLlmClient implements LlmClient, AutoCloseable {

	/** Server-side refusal fallback; the "default" form routes by refusal category. */
	static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

	private final AnthropicClient client;

	private final Clock clock;

	public AnthropicLlmClient(AnthropicClient client, Clock clock) {
		this.client = client;
		this.clock = clock;
	}

	@Override
	public LlmResponse complete(LlmPrompt prompt, @Nullable Map<String, Object> jsonSchema, LlmOptions options) {
		MessageCreateParams.Builder params = MessageCreateParams.builder()
			.model(options.model())
			.maxTokens(options.maxTokens())
			.addUserMessage(prompt.user())
			.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
			.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
		if (!prompt.system().isBlank()) {
			params.systemOfTextBlockParams(List.of(TextBlockParam.builder()
				.text(prompt.system())
				.cacheControl(CacheControlEphemeral.builder().build())
				.build()));
		}
		OutputConfig.Builder output = OutputConfig.builder().effort(effort(options.effort()));
		if (jsonSchema != null) {
			output.format(JsonOutputFormat.builder()
				.schema(JsonOutputFormat.Schema.builder()
					.putAllAdditionalProperties(jsonSchema.entrySet()
						.stream()
						.collect(Collectors.toMap(Map.Entry::getKey, entry -> JsonValue.from(entry.getValue()))))
					.build())
				.build());
		}
		params.outputConfig(output.build());

		Instant started = clock.instant();
		Message message;
		try {
			message = client.messages()
				.create(params.build(), RequestOptions.builder().timeout(options.timeout()).build());
		}
		catch (RuntimeException ex) {
			throw translate(ex, options);
		}
		Duration latency = Duration.between(started, clock.instant());

		StopReason stop = message.stopReason().orElse(null);
		if (StopReason.REFUSAL.equals(stop)) {
			throw new LlmException(LlmException.Kind.REFUSED,
					"The model declined the " + options.useCase().configKey() + " request");
		}
		if (StopReason.MAX_TOKENS.equals(stop)) {
			throw new LlmException(LlmException.Kind.TRUNCATED, "The " + options.useCase().configKey()
					+ " answer hit the limit of " + options.maxTokens() + " output tokens");
		}
		String text = message.content()
			.stream()
			.flatMap(block -> block.text().stream())
			.map(block -> block.text())
			.collect(Collectors.joining());
		return new LlmResponse(text, message.model().asString(), message.usage().inputTokens(),
				message.usage().outputTokens(), message.usage().cacheReadInputTokens().orElse(0L),
				message.usage().cacheCreationInputTokens().orElse(0L), latency);
	}

	@Override
	public String provider() {
		return "anthropic";
	}

	/** Releases the SDK's connection pool; Spring calls it on shutdown. */
	@Override
	public void close() {
		client.close();
	}

	private static OutputConfig.Effort effort(LlmOptions.Effort effort) {
		return switch (effort) {
			case LOW -> OutputConfig.Effort.LOW;
			case MEDIUM -> OutputConfig.Effort.MEDIUM;
			case HIGH -> OutputConfig.Effort.HIGH;
			case XHIGH -> OutputConfig.Effort.XHIGH;
			case MAX -> OutputConfig.Effort.MAX;
		};
	}

	/** Most specific first; the provider's own message never reaches API clients (only logs). */
	private static LlmException translate(RuntimeException ex, LlmOptions options) {
		String what = options.useCase().configKey() + " call to " + options.model();
		if (ex instanceof UnauthorizedException || ex instanceof PermissionDeniedException) {
			return new LlmException(LlmException.Kind.AUTHENTICATION,
					what + " was rejected: check the AI API key and its permissions", ex);
		}
		if (ex instanceof RateLimitException) {
			return new LlmException(LlmException.Kind.RATE_LIMITED, what + " was rate limited", ex);
		}
		if (ex instanceof BadRequestException || ex instanceof NotFoundException
				|| ex instanceof UnprocessableEntityException) {
			return new LlmException(LlmException.Kind.INVALID_REQUEST, what + " was invalid: " + ex.getMessage(), ex);
		}
		if (ex instanceof AnthropicServiceException service) {
			// 5xx and 529 overloaded
			return new LlmException(service.statusCode() >= 500 ? LlmException.Kind.UNAVAILABLE
					: LlmException.Kind.INVALID_REQUEST, what + " failed with status " + service.statusCode(), ex);
		}
		if (ex instanceof AnthropicIoException) {
			boolean timedOut = hasCause(ex, InterruptedIOException.class);
			return new LlmException(timedOut ? LlmException.Kind.TIMEOUT : LlmException.Kind.NETWORK,
					timedOut ? what + " timed out after " + options.timeout() : what + " could not connect", ex);
		}
		return new LlmException(LlmException.Kind.UNAVAILABLE, what + " failed unexpectedly", ex);
	}

	private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (type.isInstance(cause)) {
				return true;
			}
		}
		return false;
	}

}
