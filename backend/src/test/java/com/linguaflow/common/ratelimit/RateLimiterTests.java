package com.linguaflow.common.ratelimit;

import java.time.Duration;

import com.linguaflow.common.config.LinguaFlowProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTests {

	private final RateLimiter limiter = new RateLimiter("login",
			new LinguaFlowProperties.Limit(2, Duration.ofMinutes(1)));

	@Test
	void allowsUpToTheCapacityThenReportsTheWait() {
		assertThat(limiter.tryConsume("ip-1")).isEmpty();
		assertThat(limiter.tryConsume("ip-1")).isEmpty();
		assertThat(limiter.tryConsume("ip-1")).get()
			.satisfies(wait -> assertThat(wait).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(1)));
	}

	@Test
	void keysAreIndependent() {
		limiter.tryConsume("ip-2");
		limiter.tryConsume("ip-2");
		assertThat(limiter.tryConsume("ip-2")).isPresent();
		assertThat(limiter.tryConsume("ip-3")).isEmpty();
	}

	@Test
	void retryAfterIsRoundedUpToWholeSeconds() {
		assertThat(new RateLimitExceededException(Duration.ofMillis(1)).retryAfterSeconds()).isEqualTo(1);
		assertThat(new RateLimitExceededException(Duration.ofMillis(29_001)).retryAfterSeconds()).isEqualTo(30);
	}

}
