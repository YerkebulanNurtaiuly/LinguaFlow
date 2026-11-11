package com.linguaflow.ai.usage;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import com.linguaflow.ai.AiCaller;
import com.linguaflow.common.config.LinguaFlowProperties;
import org.springframework.stereotype.Component;

/**
 * The per-user daily AI allowance (AI-05): {@code daily-requests} calls and
 * {@code daily-tokens} input plus output tokens per UTC day, counted from
 * {@code ai_requests}. It's checked before each call, so concurrent calls can overshoot
 * by a few; that's acceptable for a cost guard. System calls aren't limited.
 */
@Component
public class AiQuota {

	/** Today's figures for one user, for {@code GET /api/ai/usage}. */
	public record Status(long requestsToday, int requestLimit, long tokensToday, long tokenLimit, boolean enabled,
			Instant resetsAt) {

		public boolean exceeded() {
			return enabled && (requestsToday >= requestLimit || tokensToday >= tokenLimit);
		}

	}

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm 'UTC'");

	private final AiRequestLog requests;

	private final Clock clock;

	private final LinguaFlowProperties.Ai.Quota settings;

	public AiQuota(AiRequestLog requests, Clock clock, LinguaFlowProperties properties) {
		this.requests = requests;
		this.clock = clock;
		this.settings = properties.ai().quota();
	}

	public Status status(UUID userId) {
		Instant now = clock.instant();
		LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
		Instant dayStart = today.atStartOfDay(ZoneOffset.UTC).toInstant();
		AiRequestRepository.UserUsage usage = requests.usageSince(userId, dayStart);
		return new Status(usage.requests(), settings.dailyRequests(), usage.tokens(), settings.dailyTokens(),
				settings.enabled(), today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
	}

	/** @throws AiQuotaExceededException when the caller's user has no allowance left today */
	public void check(AiCaller caller) {
		if (!settings.enabled() || caller.userId() == null) {
			return;
		}
		Status status = status(caller.userId());
		if (status.exceeded()) {
			throw new AiQuotaExceededException("Daily AI limit reached; it resets at "
					+ TIME.format(status.resetsAt().atOffset(ZoneOffset.UTC)), status.resetsAt());
		}
	}

}
