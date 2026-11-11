package com.linguaflow.ai.usage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.linguaflow.ai.AiCaller;
import com.linguaflow.ai.LlmException;
import com.linguaflow.ai.LlmOptions;
import com.linguaflow.ai.LlmResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes one {@code ai_requests} row per LLM call (AI-05), in its own transaction, so it
 * is kept even when the caller's work rolls back. A failure to log is logged and
 * swallowed: it never fails the call it describes.
 */
@Component
public class AiRequestLog {

	private static final Logger log = LoggerFactory.getLogger(AiRequestLog.class);

	private final AiRequestRepository requests;

	private final AiPricing pricing;

	private final Clock clock;

	private final TransactionTemplate tx;

	public AiRequestLog(AiRequestRepository requests, AiPricing pricing, Clock clock,
			PlatformTransactionManager transactions) {
		this.requests = requests;
		this.pricing = pricing;
		this.clock = clock;
		this.tx = new TransactionTemplate(transactions);
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public void succeeded(AiCaller caller, String promptVersion, LlmOptions options, LlmResponse response) {
		write(new AiRequest(caller.userId(), caller.jobId(), options.useCase().name(), response.model(), promptVersion,
				clamp(response.totalInputTokens()), clamp(response.outputTokens()), millis(response.latency()),
				pricing.cost(response).orElse(null), true, null, clock.instant()));
	}

	public void failed(AiCaller caller, String promptVersion, LlmOptions options, LlmException error,
			Duration latency) {
		write(new AiRequest(caller.userId(), caller.jobId(), options.useCase().name(), options.model(), promptVersion,
				null, null, millis(latency), null, false, error.kind().name(), clock.instant()));
	}

	/** The user's calls and tokens since {@code since}. */
	public AiRequestRepository.UserUsage usageSince(UUID userId, Instant since) {
		return requests.usageByUserIdSince(userId, since);
	}

	private void write(AiRequest request) {
		try {
			tx.executeWithoutResult(status -> requests.save(request));
		}
		catch (RuntimeException ex) {
			// E.g. the user was deleted meanwhile (the FK fails); the call itself is unaffected
			log.warn("Could not log an AI request for {}: {}", request.getUseCase(), ex.toString());
		}
	}

	private static Integer clamp(long tokens) {
		return (int) Math.min(Integer.MAX_VALUE, Math.max(0, tokens));
	}

	private static @Nullable Integer millis(@Nullable Duration latency) {
		return latency == null ? null : clamp(latency.toMillis());
	}

}
