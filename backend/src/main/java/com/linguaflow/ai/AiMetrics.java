package com.linguaflow.ai;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import com.linguaflow.ai.usage.AiPricing;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Micrometer metrics for AI calls (OPS-04), the live view next to the {@code ai_requests}
 * log (AI-05). Tags are kept to bounded sets, so they never multiply series per user or
 * prompt:
 * <ul>
 * <li>{@code linguaflow.ai.calls} (timer): every call that reached the provider, by
 * {@code use_case}, {@code model} (the configured one) and {@code outcome}
 * ({@code success} or the {@link LlmException.Kind} in lower case). Latency percentiles
 * are published as a histogram.</li>
 * <li>{@code linguaflow.ai.tokens} (counter): tokens by {@code use_case}, {@code model} and
 * {@code type} (input, output, cache_read, cache_write).</li>
 * <li>{@code linguaflow.ai.cost} (counter, USD): the estimated cost of successful calls.</li>
 * <li>{@code linguaflow.ai.refused} (counter): calls stopped before the provider, by
 * {@code reason} ({@code quota} or {@code circuit_open}).</li>
 * <li>{@code linguaflow.ai.cache} (counter): shared-cache lookups by {@code result} (hit,
 * miss).</li>
 * </ul>
 */
@Component
public class AiMetrics {

	private final MeterRegistry registry;

	private final @Nullable AiPricing pricing;

	@Autowired
	public AiMetrics(MeterRegistry registry, AiPricing pricing) {
		this.registry = registry;
		this.pricing = pricing;
	}

	AiMetrics(MeterRegistry registry) {
		this.registry = registry;
		this.pricing = null;
	}

	/** Metrics into a throwaway registry, without cost estimates: for unit tests. */
	public static AiMetrics discarding() {
		return new AiMetrics(new SimpleMeterRegistry());
	}

	void succeeded(LlmOptions options, LlmResponse response, Duration took) {
		String useCase = useCase(options);
		calls(useCase, options.model(), "success").record(took);
		tokens(useCase, options.model(), "input", response.inputTokens());
		tokens(useCase, options.model(), "output", response.outputTokens());
		tokens(useCase, options.model(), "cache_read", response.cacheReadTokens());
		tokens(useCase, options.model(), "cache_write", response.cacheWriteTokens());
		Optional<BigDecimal> cost = pricing == null ? Optional.empty() : pricing.cost(response);
		cost.ifPresent(usd -> Counter.builder("linguaflow.ai.cost")
			.description("Estimated cost of AI calls")
			.baseUnit("usd")
			.tags("use_case", useCase, "model", options.model())
			.register(registry)
			.increment(usd.doubleValue()));
	}

	void failed(LlmOptions options, LlmException.Kind kind, Duration took) {
		calls(useCase(options), options.model(), kind.name().toLowerCase(Locale.ROOT)).record(took);
	}

	void refused(AiUseCase useCase, String reason) {
		Counter.builder("linguaflow.ai.refused")
			.description("AI calls refused before reaching the provider")
			.tags("use_case", useCase.configKey(), "reason", reason)
			.register(registry)
			.increment();
	}

	/** A shared-cache lookup (AI-06): {@code hit} true when a stored answer was used. */
	public void cacheLookup(AiUseCase useCase, boolean hit) {
		Counter.builder("linguaflow.ai.cache")
			.description("Shared AI cache lookups")
			.tags("use_case", useCase.configKey(), "result", hit ? "hit" : "miss")
			.register(registry)
			.increment();
	}

	private Timer calls(String useCase, String model, String outcome) {
		return Timer.builder("linguaflow.ai.calls")
			.description("AI calls that reached the provider")
			.tags("use_case", useCase, "model", model, "outcome", outcome)
			.publishPercentileHistogram()
			.minimumExpectedValue(Duration.ofMillis(100))
			.maximumExpectedValue(Duration.ofMinutes(3))
			.register(registry);
	}

	private void tokens(String useCase, String model, String type, long count) {
		if (count <= 0) {
			return;
		}
		Counter.builder("linguaflow.ai.tokens")
			.description("Tokens used by AI calls")
			.tags("use_case", useCase, "model", model, "type", type)
			.register(registry)
			.increment(count);
	}

	private static String useCase(LlmOptions options) {
		return options.useCase().configKey();
	}

}
