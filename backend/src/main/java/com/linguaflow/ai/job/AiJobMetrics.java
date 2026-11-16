package com.linguaflow.ai.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The AI job queue as a gauge (OPS-04): {@code linguaflow.ai.jobs} by {@code status}, all
 * users together. A growing QUEUED count means the worker can't keep up; DEAD and FAILED
 * only grow, so a dashboard shows their increase. The counts are read with one grouped
 * query, at most every {@link #MAX_AGE}, however often the endpoint is scraped.
 */
@Component
class AiJobMetrics {

	private static final Logger log = LoggerFactory.getLogger(AiJobMetrics.class);

	static final Duration MAX_AGE = Duration.ofSeconds(15);

	private final JdbcTemplate jdbc;

	private final Clock clock;

	private Map<AiJobStatus, Long> counts = new EnumMap<>(AiJobStatus.class);

	private Instant readAt = Instant.MIN;

	AiJobMetrics(JdbcTemplate jdbc, Clock clock, MeterRegistry registry) {
		this.jdbc = jdbc;
		this.clock = clock;
		for (AiJobStatus status : AiJobStatus.values()) {
			Gauge.builder("linguaflow.ai.jobs", this, metrics -> metrics.count(status))
				.description("AI jobs by status")
				.tag("status", status.name().toLowerCase(Locale.ROOT))
				.register(registry);
		}
	}

	synchronized double count(AiJobStatus status) {
		Instant now = clock.instant();
		if (readAt.plus(MAX_AGE).isBefore(now)) {
			try {
				Map<AiJobStatus, Long> fresh = new EnumMap<>(AiJobStatus.class);
				jdbc.query("select status, count(*) from ai_jobs group by status",
						row -> {
							fresh.put(AiJobStatus.valueOf(row.getString(1)), row.getLong(2));
						});
				counts = fresh;
			}
			catch (RuntimeException ex) {
				log.warn("Couldn't count AI jobs for metrics: {}", ex.toString());
			}
			readAt = now;
		}
		return counts.getOrDefault(status, 0L);
	}

}
