package com.linguaflow.exercise;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.common.security.CurrentUserId;
import com.linguaflow.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reporting bad exercises (EX-07), and the admins' list of reports. */
@RestController
@Tag(name = "Exercises", description = "Grammar exercises written for your topics and words")
class ExerciseReportController {

	private final ExerciseReportService reports;

	ExerciseReportController(ExerciseReportService reports) {
		this.reports = reports;
	}

	@Schema(name = "ReportExerciseRequest")
	record ReportRequest(@Schema(description = "What's wrong with it, e.g. \"two options are correct\"") @Nullable @Size(
			max = Exercise.MAX_REPORT_REASON) String reason) {
	}

	@Schema(name = "ReportedExercise", description = "A reported exercise with its solution, for prompt tuning")
	record ReportedResponse(UUID id, UUID exerciseSetId, String languageCode, ExerciseType type,
			ExerciseDifficulty difficulty,
			@Schema(oneOf = { ExercisePrompt.FillBlank.class, ExercisePrompt.MultipleChoice.class,
					ExercisePrompt.Translation.class, ExercisePrompt.SentenceConstruction.class,
					ExercisePrompt.ErrorCorrection.class, ExercisePrompt.WordOrder.class,
					ExercisePrompt.Transformation.class }) ExercisePrompt prompt,
			@Schema(oneOf = { ExerciseSolution.Blanks.class, ExerciseSolution.Choice.class,
					ExerciseSolution.TextAnswer.class,
					ExerciseSolution.TokenOrder.class }) ExerciseSolution solution,
			@Schema(description = "The learner's reason, if they gave one") String reportReason, Instant reportedAt,
			Instant createdAt) {

		static ReportedResponse from(ReportedExercise reported) {
			Exercise exercise = reported.exercise();
			ExerciseContent content = exercise.content();
			return new ReportedResponse(exercise.getId(), exercise.getExerciseSetId(), reported.languageCode(),
					exercise.getType(), exercise.getDifficulty(), content.prompt(), content.solution(),
					exercise.getReportReason(), exercise.getReportedAt(), exercise.getCreatedAt());
		}

	}

	@PostMapping("/api/exercises/{id}/report")
	@Operation(summary = "Report a bad exercise",
			description = "Flags the exercise as wrong or unclear. It stays in its set marked as reported, is left out "
					+ "of practice, and the report helps improve how exercises are written. Reporting again updates "
					+ "the reason.")
	@ApiResponse(responseCode = "200", description = "The exercise, now reported")
	ExerciseSetController.ExerciseResponse report(@CurrentUserId UUID userId, @PathVariable UUID id,
			@Valid @RequestBody(required = false) @Nullable ReportRequest request) {
		return ExerciseSetController.ExerciseResponse
			.from(reports.report(userId, id, request == null ? null : request.reason()));
	}

	@DeleteMapping("/api/exercises/{id}/report")
	@Operation(summary = "Take a report back")
	@ApiResponse(responseCode = "200", description = "The exercise, no longer reported")
	ExerciseSetController.ExerciseResponse withdraw(@CurrentUserId UUID userId, @PathVariable UUID id) {
		return ExerciseSetController.ExerciseResponse.from(reports.withdraw(userId, id));
	}

	@GetMapping("/api/admin/exercise-reports")
	@PreAuthorize("hasRole('ADMIN')")
	@Operation(summary = "Reported exercises (admin)",
			description = "Every user's reported exercises with their prompts and solutions, latest report first, to "
					+ "tune the generation prompts. Users aren't named.")
	@ApiResponse(responseCode = "200", description = "One page of reported exercises")
	PageResponse<ReportedResponse> reported(
			@Parameter(description = "Only these types (repeatable)") @RequestParam(
					required = false) @Nullable List<ExerciseType> type,
			Pageable pageable) {
		// The order is fixed (latest report first); a sort parameter is ignored
		var page = reports.reported(type, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
		return PageResponse.from(new PageImpl<>(page.getContent(), PageRequest.of(pageable.getPageNumber(),
				pageable.getPageSize()), page.getTotalElements()), ReportedResponse::from);
	}

}
