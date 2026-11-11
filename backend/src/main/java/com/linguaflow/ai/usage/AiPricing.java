package com.linguaflow.ai.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.linguaflow.ai.LlmResponse;
import com.linguaflow.common.config.LinguaFlowProperties;
import com.linguaflow.common.config.LinguaFlowProperties.Ai.Price;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Estimates what a call cost (AI-05) from its token counts and the model's price per
 * million tokens. The built-in prices are the provider's published standard rates as of
 * 2026-11-11 (5-minute cache writes; no batch, fast-mode or regional premiums).
 * {@code linguaflow.ai.pricing.<model>} adds or overrides models when prices change.
 * <p>
 * A model matches the longest priced ID it starts with, so a dated ID like
 * {@code claude-opus-5-5-20261101} uses the {@code claude-opus-5-5} price. The fake
 * client's models cost nothing; an unknown model has no estimate (logged once).
 */
@Component
public class AiPricing {

	private static final Logger log = LoggerFactory.getLogger(AiPricing.class);

	private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

	static final Map<String, Price> BUILT_IN = Map.of(
			"claude-fable-5-2", price("8", "10", "0.20", "40"),
			"claude-opus-5-5", price("4", "5", "0.20", "20"),
			"claude-sonnet-5-5", price("2", "2.50", "0.10", "10"),
			"claude-haiku-5-5", price("0.10", "0.125", "0.01", "0.50"));

	private final Map<String, Price> prices;

	private final Set<String> warned = ConcurrentHashMap.newKeySet();

	public AiPricing(LinguaFlowProperties properties) {
		Map<String, Price> all = new HashMap<>(BUILT_IN);
		all.putAll(properties.ai().pricing());
		this.prices = Map.copyOf(all);
	}

	/** The estimated cost in USD, rounded to millionths, or empty for an unknown model. */
	public Optional<BigDecimal> cost(LlmResponse response) {
		if (response.model().startsWith("fake-")) {
			return Optional.of(BigDecimal.ZERO.setScale(6));
		}
		Optional<Price> price = priceOf(response.model());
		if (price.isEmpty()) {
			if (warned.add(response.model())) {
				log.warn("No price for AI model {}; set linguaflow.ai.pricing.{}.* to estimate its cost",
						response.model(), response.model());
			}
			return Optional.empty();
		}
		Price p = price.get();
		BigDecimal micro = p.input()
			.multiply(BigDecimal.valueOf(response.inputTokens()))
			.add(p.cacheWrite().multiply(BigDecimal.valueOf(response.cacheWriteTokens())))
			.add(p.cacheRead().multiply(BigDecimal.valueOf(response.cacheReadTokens())))
			.add(p.output().multiply(BigDecimal.valueOf(response.outputTokens())));
		return Optional.of(micro.divide(MILLION, 6, RoundingMode.HALF_UP));
	}

	Optional<Price> priceOf(String model) {
		return prices.entrySet()
			.stream()
			.filter(entry -> model.equals(entry.getKey()) || model.startsWith(entry.getKey() + "-"))
			.max(Comparator.comparingInt(entry -> entry.getKey().length()))
			.map(Map.Entry::getValue);
	}

	private static Price price(String input, String cacheWrite, String cacheRead, String output) {
		return new Price(new BigDecimal(input), new BigDecimal(cacheWrite), new BigDecimal(cacheRead),
				new BigDecimal(output));
	}

}
