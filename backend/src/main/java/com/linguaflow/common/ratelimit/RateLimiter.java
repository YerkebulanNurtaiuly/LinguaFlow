package com.linguaflow.common.ratelimit;

import java.time.Duration;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.linguaflow.common.config.LinguaFlowProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

/**
 * In-memory token buckets keyed by (limit name, client key). Idle buckets are evicted after
 * twice their period, and the total is capped, so memory stays bounded under a flood of
 * distinct IPs. Single-instance only: with several app instances each keeps its own
 * buckets (move to Bucket4j's Redis/JDBC backend if the app is scaled out).
 */
public class RateLimiter {

	private static final long MAX_TRACKED_KEYS = 100_000;

	private final String name;

	private final LinguaFlowProperties.Limit limit;

	private final Cache<String, Bucket> buckets;

	public RateLimiter(String name, LinguaFlowProperties.Limit limit) {
		this.name = name;
		this.limit = limit;
		this.buckets = Caffeine.newBuilder()
			.expireAfterAccess(limit.period().multipliedBy(2))
			.maximumSize(MAX_TRACKED_KEYS)
			.build();
	}

	public String name() {
		return name;
	}

	/** Consumes one token for {@code key}; returns how long to wait if none is left. */
	public Optional<Duration> tryConsume(String key) {
		Bucket bucket = buckets.get(key, k -> newBucket());
		ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
		return probe.isConsumed() ? Optional.empty() : Optional.of(Duration.ofNanos(probe.getNanosToWaitForRefill()));
	}

	private Bucket newBucket() {
		return Bucket.builder()
			.addLimit(Bandwidth.builder().capacity(limit.requests()).refillGreedy(limit.requests(), limit.period()).build())
			.build();
	}

}
