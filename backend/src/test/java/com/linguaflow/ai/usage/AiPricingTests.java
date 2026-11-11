package com.linguaflow.ai.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import com.linguaflow.ai.LlmResponse;
import com.linguaflow.common.config.LinguaFlowProperties;
import com.linguaflow.common.config.LinguaFlowProperties.Ai.Price;
import org.junit.jupiter.api.Test;

class AiPricingTests {

	private static AiPricing pricing(Map<String, Price> overrides) {
		LinguaFlowProperties properties = mock(LinguaFlowProperties.class);
		when(properties.ai())
			.thenReturn(new LinguaFlowProperties.Ai(null, null, null, null, null, null, null, null, overrides, null));
		return new AiPricing(properties);
	}

	private static LlmResponse response(String model, long input, long output, long cacheRead, long cacheWrite) {
		return new LlmResponse("{}", model, input, output, cacheRead, cacheWrite, Duration.ofMillis(10));
	}

	@Test
	void pricesEachKindOfTokenAtItsRate() {
		// Opus 5.5: $4 input, $5 cache write, $0.20 cache read, $20 output per million tokens
		BigDecimal cost = pricing(Map.of()).cost(response("claude-opus-5-5", 1_000, 500, 200, 100)).orElseThrow();
		// 4000 + 500 + 40 + 10000 millionths of a dollar
		assertThat(cost).isEqualByComparingTo("0.014540");
		assertThat(cost.scale()).isEqualTo(6);
	}

	@Test
	void matchesDatedModelIdsAndTakesOverrides() {
		AiPricing pricing = pricing(Map.of("claude-opus-5-5",
				new Price(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE), "my-model",
				new Price(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN)));

		assertThat(pricing.cost(response("claude-opus-5-5-20261101", 1_000_000, 0, 0, 0))).contains(new BigDecimal("1.000000"));
		assertThat(pricing.cost(response("my-model", 100_000, 100_000, 0, 0))).contains(new BigDecimal("2.000000"));
		assertThat(pricing.cost(response("claude-haiku-5-5", 1_000_000, 1_000_000, 0, 0)))
			.contains(new BigDecimal("0.600000"));
	}

	@Test
	void theFakeIsFreeAndUnknownModelsHaveNoEstimate() {
		AiPricing pricing = pricing(Map.of());
		assertThat(pricing.cost(response("fake-claude-opus-5-5", 10_000, 10_000, 0, 0))).contains(new BigDecimal("0.000000"));
		assertThat(pricing.cost(response("gpt-unknown", 10, 10, 0, 0))).isEmpty();
		// A prefix of a word isn't a match
		assertThat(pricing.cost(response("claude-opus-5-55", 10, 10, 0, 0))).isEmpty();
	}

}
