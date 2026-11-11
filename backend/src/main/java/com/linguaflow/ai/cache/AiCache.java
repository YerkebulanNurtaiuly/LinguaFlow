package com.linguaflow.ai.cache;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import com.linguaflow.common.config.LinguaFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Results of AI calls shared between users (AI-06), so a common word like "Hund" is
 * enriched once, not once per learner. Only language data may be cached; see
 * {@link AiCacheKey}.
 * <ul>
 * <li>An entry older than {@code ttl} (180 days) is a miss, and the new result replaces
 * it.</li>
 * <li>An entry that no longer fits the answer record (its shape changed) is a miss too.
 * Bump the template version when the answer shape changes, which starts a fresh
 * cache.</li>
 * <li>The cache never breaks the work it speeds up. Read and write failures are logged
 * and treated as a miss or a skipped write, in their own transactions, so they don't
 * roll back the caller.</li>
 * </ul>
 * Use it through {@code StructuredLlm.completeCached}, which also validates a hit again.
 */
@Component
public class AiCache {

	private static final Logger log = LoggerFactory.getLogger(AiCache.class);

	private static final JsonMapper JSON = JsonMapper.builder()
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.build();

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	private final AiCacheRepository entries;

	private final Clock clock;

	private final LinguaFlowProperties.Ai.Cache settings;

	private final TransactionTemplate tx;

	public AiCache(AiCacheRepository entries, Clock clock, LinguaFlowProperties properties,
			PlatformTransactionManager transactions) {
		this.entries = entries;
		this.clock = clock;
		this.settings = properties.ai().cache();
		this.tx = new TransactionTemplate(transactions);
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** The cached result for {@code key}, if there's a fresh one that fits {@code type}. */
	public <T> Optional<T> get(AiCacheKey key, Class<T> type) {
		if (!settings.enabled()) {
			return Optional.empty();
		}
		String hash = key.hash();
		try {
			return tx.execute(status -> {
				Instant now = clock.instant();
				Optional<AiCacheEntry> entry = entries.findById(hash)
					.filter(found -> found.getCreatedAt().plus(settings.ttl()).isAfter(now));
				if (entry.isEmpty()) {
					return Optional.<T>empty();
				}
				T value;
				try {
					value = JSON.convertValue(entry.get().getPayload(), type);
				}
				catch (JacksonException | IllegalArgumentException ex) {
					log.info("AI cache entry for {} no longer fits {}; treating it as a miss", key.useCase(),
							type.getSimpleName());
					return Optional.<T>empty();
				}
				entries.recordHit(hash, now);
				return Optional.ofNullable(value);
			});
		}
		catch (RuntimeException ex) {
			log.warn("AI cache read failed for {}: {}", key.useCase(), ex.toString());
			return Optional.empty();
		}
	}

	/** Stores {@code value} (a record) under {@code key}; skipped when it's too large. */
	public void put(AiCacheKey key, Object value) {
		if (!settings.enabled()) {
			return;
		}
		try {
			Map<String, Object> payload = JSON.convertValue(value, MAP);
			String json = JSON.writeValueAsString(payload);
			if (json.getBytes(StandardCharsets.UTF_8).length > settings.maxPayloadBytes()) {
				log.info("AI result for {} is larger than {} bytes; not cached", key.useCase(),
						settings.maxPayloadBytes());
				return;
			}
			tx.executeWithoutResult(status -> entries.upsert(key.hash(), key.useCase().name(), key.languageCode(),
					key.promptVersion(), json, clock.instant()));
		}
		catch (RuntimeException ex) {
			log.warn("AI cache write failed for {}: {}", key.useCase(), ex.toString());
		}
	}

}
