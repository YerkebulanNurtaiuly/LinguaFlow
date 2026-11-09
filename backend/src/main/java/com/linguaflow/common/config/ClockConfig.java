package com.linguaflow.common.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Inject {@link Clock} instead of calling {@code Instant.now()} directly, so time-based
 * logic (auditing, SRS scheduling, token expiry) can be tested with a fixed clock.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
