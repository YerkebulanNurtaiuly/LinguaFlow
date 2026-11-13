package com.linguaflow.progress;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.common.security.CurrentUserId;
import com.linguaflow.progress.WeaknessScores.ReasonCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Progress analysis (PRG-03 onwards). */
@RestController
@RequestMapping("/api/progress")
@Tag(name = "Progress", description = "How your learning is going")
class ProgressController {

	private final WeakPointService weakPoints;

	ProgressController(WeakPointService weakPoints) {
		this.weakPoints = weakPoints;
	}

	@Schema(name = "WeakPointReason", description = "Why it counts as weak, with the number behind it")
	record ReasonResponse(ReasonCode code,
			@Schema(description = "LOW_ACCURACY: the recent accuracy (0–1); FREQUENT_MISTAKE: how many mistakes "
					+ "of errorType; LAPSES: the most lapses on one card; NOT_PRACTISED_RECENTLY: days since") double value,
			@Schema(description = "FREQUENT_MISTAKE only: the error type, e.g. WRONG_CASE") String errorType) {
	}

	@Schema(name = "WeakPoint")
	record WeakPointResponse(UUID id, @Schema(description = "The topic's title or the word") String name,
			@Schema(description = "Words only") String translation, KnowledgeStatus status,
			@Schema(description = "0 (solid) to 1 (weakest)") double score,
			@Schema(description = "Attempts (topics) or answers (words) counted") int answers,
			@Schema(description = "The recent accuracy, 0–1") Double recentAccuracy, Instant lastPracticedAt,
			List<ReasonResponse> reasons) {

		static WeakPointResponse from(WeakPointService.WeakPoint point) {
			return new WeakPointResponse(point.id(), point.name(), point.translation(), point.status(),
					point.weakness().score(), point.answers(), point.recentAccuracy(), point.lastPracticedAt(),
					point.weakness()
						.reasons()
						.stream()
						.map(reason -> new ReasonResponse(reason.code(), reason.value(), reason.errorType()))
						.toList());
		}

	}

	@Schema(name = "WeakPoints")
	record WeakPointsResponse(@Schema(description = "Weakest first") List<WeakPointResponse> grammarTopics,
			@Schema(description = "Weakest first") List<WeakPointResponse> words) {
	}

	@GetMapping("/weak-points")
	@Operation(summary = "Your weak points",
			description = "The active language's weakest grammar topics and words that you have practised, weakest "
					+ "first, each with why: low recent accuracy, a frequent mistake, card lapses or a long break. "
					+ "Scores below " + WeakPointService.MIN_SCORE + " aren't listed.")
	@ApiResponse(responseCode = "200", description = "The weak points")
	WeakPointsResponse weakPoints(@CurrentUserId UUID userId,
			@Parameter(description = "At most this many of each, 1–" + WeakPointService.MAX_LIMIT) @RequestParam(
					defaultValue = "10") int limit) {
		if (limit < 1 || limit > WeakPointService.MAX_LIMIT) {
			throw new InvalidFieldException("limit", "must be between 1 and " + WeakPointService.MAX_LIMIT);
		}
		var found = weakPoints.weakPoints(userId, limit);
		return new WeakPointsResponse(found.topics().stream().map(WeakPointResponse::from).toList(),
				found.words().stream().map(WeakPointResponse::from).toList());
	}

}
