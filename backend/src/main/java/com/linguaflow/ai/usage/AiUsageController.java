package com.linguaflow.ai.usage;

import java.time.LocalDate;
import java.util.UUID;

import com.linguaflow.ai.usage.AiUsageDtos.MyAiUsage;
import com.linguaflow.ai.usage.AiUsageDtos.Summary;
import com.linguaflow.common.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** AI usage: the caller's own allowance, and the admins' summary across users (AI-05). */
@RestController
@Tag(name = "AI usage", description = "Daily AI limits and what AI calls cost")
public class AiUsageController {

	private final AiQuota quota;

	private final AiUsageReport report;

	public AiUsageController(AiQuota quota, AiUsageReport report) {
		this.quota = quota;
		this.report = report;
	}

	@GetMapping("/api/ai/usage")
	@Operation(summary = "My AI usage today",
			description = "Calls and tokens used today (UTC) against the daily limits. When the allowance is used up, "
					+ "new AI work such as enriching words waits until resetsAt.")
	@ApiResponse(responseCode = "200", description = "Today's usage")
	MyAiUsage mine(@CurrentUserId UUID userId) {
		return MyAiUsage.from(quota.status(userId));
	}

	@GetMapping("/api/admin/ai-usage")
	@PreAuthorize("hasRole('ADMIN')")
	@Operation(summary = "AI usage summary (admin)",
			description = "LLM calls, failures, tokens and estimated cost across all users, by use case, model and "
					+ "UTC day, plus the users with the highest cost. Covers at most 366 days.")
	@ApiResponse(responseCode = "200", description = "The summary")
	Summary summary(
			@Parameter(description = "First UTC day, inclusive (default: 29 days before to)") @RequestParam(
					required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate from,
			@Parameter(description = "Last UTC day, inclusive (default: today)") @RequestParam(
					required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate to) {
		return report.summary(from, to);
	}

}
