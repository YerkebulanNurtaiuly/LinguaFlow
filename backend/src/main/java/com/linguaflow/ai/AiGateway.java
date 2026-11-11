package com.linguaflow.ai;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.linguaflow.ai.usage.AiQuota;
import com.linguaflow.ai.usage.AiRequestLog;
import com.linguaflow.common.config.LinguaFlowProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The one way LLM calls leave the app (AI-05). Around every {@link LlmClient} call it:
 * <ol>
 * <li>checks the user's daily quota ({@link AiQuota}), before spending anything;</li>
 * <li>runs the call through a circuit breaker. After many timeouts, overloads or network
 * errors, it refuses calls for {@code open-for} (60 s) with a retryable
 * {@code UNAVAILABLE}, so a struggling provider isn't hammered and jobs back off. Other
 * errors (bad request, refusal) say nothing about the provider's health and don't
 * count;</li>
 * <li>logs the call in {@code ai_requests}, with tokens, latency, cost and outcome.</li>
 * </ol>
 * Timeouts are per use case, in {@link LlmOptions} (AI-01). Calls refused by the quota or
 * the open circuit never reach the provider and aren't logged as calls.
 */
@Component
public class AiGateway {

	private static final Logger log = LoggerFactory.getLogger(AiGateway.class);

	/** Failures that mean the provider is struggling. */
	static final Set<LlmException.Kind> PROVIDER_FAILURES = EnumSet.of(LlmException.Kind.TIMEOUT,
			LlmException.Kind.UNAVAILABLE, LlmException.Kind.NETWORK);

	private final LlmClient client;

	private final AiQuota quota;

	private final AiRequestLog requests;

	private final CircuitBreaker breaker;

	public AiGateway(LlmClient client, AiQuota quota, AiRequestLog requests, LinguaFlowProperties properties) {
		this.client = client;
		this.quota = quota;
		this.requests = requests;
		LinguaFlowProperties.Ai.Breaker settings = properties.ai().circuitBreaker();
		this.breaker = CircuitBreaker.of("llm-" + client.provider(), CircuitBreakerConfig.custom()
			.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
			.slidingWindowSize(settings.windowSize())
			.minimumNumberOfCalls(settings.minimumCalls())
			.failureRateThreshold(settings.failureRateThreshold())
			.waitDurationInOpenState(settings.openFor())
			.permittedNumberOfCallsInHalfOpenState(2)
			.recordException(ex -> ex instanceof LlmException llm && PROVIDER_FAILURES.contains(llm.kind()))
			.build());
		this.breaker.getEventPublisher()
			.onStateTransition(event -> log.warn("AI provider circuit {}: {}", event.getCircuitBreakerName(),
					event.getStateTransition()));
	}

	/**
	 * @param promptVersion the template's version tag, e.g. {@code vocabulary-enrichment/v1}
	 * @throws com.linguaflow.ai.usage.AiQuotaExceededException when the user has no
	 * allowance left today
	 * @throws LlmException when the call fails or the circuit is open
	 */
	public LlmResponse complete(AiCaller caller, String promptVersion, LlmPrompt prompt,
			@Nullable Map<String, Object> jsonSchema, LlmOptions options) {
		quota.check(caller);
		long started = System.nanoTime();
		try {
			LlmResponse response = breaker.executeSupplier(() -> client.complete(prompt, jsonSchema, options));
			requests.succeeded(caller, promptVersion, options, response);
			return response;
		}
		catch (CallNotPermittedException ex) {
			throw new LlmException(LlmException.Kind.UNAVAILABLE,
					"AI calls are paused for a moment after repeated provider failures", ex);
		}
		catch (LlmException ex) {
			requests.failed(caller, promptVersion, options, ex, Duration.ofNanos(System.nanoTime() - started));
			throw ex;
		}
	}

	CircuitBreaker.State circuitState() {
		return breaker.getState();
	}

}
