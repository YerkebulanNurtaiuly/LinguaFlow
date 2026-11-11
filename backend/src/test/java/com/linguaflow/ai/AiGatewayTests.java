package com.linguaflow.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import com.linguaflow.ai.LlmOptions.Effort;
import com.linguaflow.ai.usage.AiQuota;
import com.linguaflow.ai.usage.AiQuotaExceededException;
import com.linguaflow.ai.usage.AiRequestLog;
import com.linguaflow.common.config.LinguaFlowProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

class AiGatewayTests {

	private static final LlmOptions OPTIONS = new LlmOptions(AiUseCase.VOCABULARY_ENRICHMENT, "claude-opus-5-5",
			Effort.LOW, 1_000, Duration.ofSeconds(5));

	private static final LlmPrompt PROMPT = new LlmPrompt("sys", "Hund");

	private static final AiCaller ANNA = AiCaller.user(java.util.UUID.randomUUID());

	private final FakeLlmClient fake = new FakeLlmClient();

	private final AiQuota quota = mock(AiQuota.class);

	private final AiRequestLog requests = mock(AiRequestLog.class);

	private final AiGateway gateway = new AiGateway(fake, quota, requests, properties(Duration.ofMillis(300)));

	private static LinguaFlowProperties properties(Duration openFor) {
		LinguaFlowProperties properties = mock(LinguaFlowProperties.class);
		when(properties.ai()).thenReturn(new LinguaFlowProperties.Ai(null, null, null, null, null, null, null,
				new LinguaFlowProperties.Ai.Breaker(50, 4, 4, openFor), null));
		return properties;
	}

	private LlmResponse call() {
		return gateway.complete(ANNA, "vocabulary-enrichment/v1", PROMPT, null, OPTIONS);
	}

	@Test
	void logsSuccessfulAndFailedCalls() {
		LlmResponse response = call();
		verify(requests).succeeded(ANNA, "vocabulary-enrichment/v1", OPTIONS, response);

		LlmException failure = new LlmException(LlmException.Kind.RATE_LIMITED, "slow down");
		fake.failNext(failure);
		assertThatThrownBy(this::call).isSameAs(failure);
		verify(requests).failed(eq(ANNA), eq("vocabulary-enrichment/v1"), eq(OPTIONS), eq(failure), any());
	}

	@Test
	void checksTheQuotaBeforeSpendingAnything() {
		AiQuotaExceededException limit = new AiQuotaExceededException("limit", Instant.now());
		doThrow(limit).when(quota).check(ANNA);

		assertThatThrownBy(this::call).isSameAs(limit);
		assertThat(fake.calls()).isEmpty();
		verify(requests, never()).failed(any(), any(), any(), any(), any());
	}

	@Test
	void opensTheCircuitAfterRepeatedProviderFailures() throws InterruptedException {
		for (int i = 0; i < 4; i++) {
			fake.failNext(new LlmException(LlmException.Kind.UNAVAILABLE, "overloaded"));
			assertThatThrownBy(this::call).isInstanceOf(LlmException.class);
		}
		assertThat(gateway.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

		// While open, calls are refused without reaching the provider, with a retryable error
		int before = fake.calls().size();
		assertThatThrownBy(this::call).isInstanceOfSatisfying(LlmException.class, ex -> {
			assertThat(ex.kind()).isEqualTo(LlmException.Kind.UNAVAILABLE);
			assertThat(ex.kind().retryable()).isTrue();
			assertThat(ex.getMessage()).contains("paused");
		});
		assertThat(fake.calls()).hasSize(before);

		// After open-for, test calls go through and successes close it again
		Thread.sleep(400);
		call();
		call();
		assertThat(gateway.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void errorsThatSayNothingAboutTheProviderDoNotOpenTheCircuit() {
		for (int i = 0; i < 8; i++) {
			fake.failNext(new LlmException(LlmException.Kind.INVALID_REQUEST, "bad"));
			assertThatThrownBy(this::call).isInstanceOf(LlmException.class);
		}
		assertThat(gateway.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

}
