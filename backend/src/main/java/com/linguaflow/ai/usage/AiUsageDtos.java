package com.linguaflow.ai.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Response bodies of the AI usage endpoints (AI-05). */
public final class AiUsageDtos {

	private AiUsageDtos() {
	}

	@Schema(name = "MyAiUsage", description = "The caller's AI usage today (UTC) and the daily limits")
	public record MyAiUsage(long requestsToday, int requestLimit, long tokensToday, long tokenLimit,
			@Schema(description = "Whether the limits apply") boolean limited,
			@Schema(description = "Whether today's allowance is used up; new AI work waits until resetsAt") boolean exceeded,
			Instant resetsAt) {

		static MyAiUsage from(AiQuota.Status status) {
			return new MyAiUsage(status.requestsToday(), status.requestLimit(), status.tokensToday(),
					status.tokenLimit(), status.enabled(), status.exceeded(), status.resetsAt());
		}

	}

	@Schema(name = "AiUsageTotals", description = "LLM calls and their estimated cost")
	public record Totals(long requests, long failures, long inputTokens, long outputTokens,
			@Schema(description = "Estimated cost in USD; calls to models without a price count as 0") BigDecimal costUsd) {

		static final Totals EMPTY = new Totals(0, 0, 0, 0, BigDecimal.ZERO.setScale(6));

		Totals plus(AiRequestRepository.UsageRow row) {
			return new Totals(requests + row.getRequests(), failures + row.getFailures(),
					inputTokens + row.getInputTokens(), outputTokens + row.getOutputTokens(),
					costUsd.add(row.getCost()).setScale(6, java.math.RoundingMode.HALF_UP));
		}

		Totals plus(Totals other) {
			return new Totals(requests + other.requests, failures + other.failures, inputTokens + other.inputTokens,
					outputTokens + other.outputTokens, costUsd.add(other.costUsd));
		}

	}

	@Schema(name = "AiUsageByUseCase")
	public record ByUseCase(String useCase, Totals totals) {
	}

	@Schema(name = "AiUsageByModel")
	public record ByModel(String model, Totals totals) {
	}

	@Schema(name = "AiUsageByDay")
	public record ByDay(LocalDate day, Totals totals) {
	}

	@Schema(name = "AiUsageTopUser")
	public record TopUser(UUID userId, long requests, BigDecimal costUsd) {
	}

	@Schema(name = "AiUsageSummary", description = "AI usage across all users for a range of UTC days")
	public record Summary(LocalDate from, LocalDate to, Totals totals, List<ByUseCase> byUseCase,
			List<ByModel> byModel, @Schema(description = "Only days with calls, oldest first") List<ByDay> byDay,
			@Schema(description = "Up to 10 users by estimated cost") List<TopUser> topUsers) {
	}

}
