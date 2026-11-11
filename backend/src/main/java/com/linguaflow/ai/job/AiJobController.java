package com.linguaflow.ai.job;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.linguaflow.ai.job.AiJobDtos.JobResponse;
import com.linguaflow.ai.job.AiJobDtos.Progress;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.common.security.CurrentUserId;
import com.linguaflow.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.SortDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Status of the learner's background AI work (AI-08), for the UI to poll. Polling rather
 * than a stream: jobs take seconds to minutes, and a request every few seconds is simple
 * and works behind any proxy.
 */
@RestController
@RequestMapping("/api/ai/jobs")
@Tag(name = "AI jobs", description = "Progress of background AI work such as enriching words")
public class AiJobController {

	private static final Map<String, String> SORTABLE = Map.of("createdAt", "createdAt", "updatedAt", "updatedAt");

	private final AiJobStatusService jobs;

	public AiJobController(AiJobStatusService jobs) {
		this.jobs = jobs;
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get an AI job",
			description = "Poll every few seconds until finished is true. A QUEUED job with waiting set is retrying "
					+ "after a temporary problem (RETRY) or waiting for the daily AI allowance (DAILY_LIMIT), until "
					+ "nextRunAt.")
	@ApiResponse(responseCode = "200", description = "The job")
	JobResponse get(@CurrentUserId UUID userId, @PathVariable UUID id) {
		return JobResponse.from(jobs.get(userId, id));
	}

	@GetMapping
	@Operation(summary = "List AI jobs", description = "Your AI jobs, newest first. Sortable by createdAt, updatedAt.")
	@ApiResponse(responseCode = "200", description = "One page of jobs")
	PageResponse<JobResponse> list(@CurrentUserId UUID userId,
			@Parameter(description = "Only these statuses (repeatable)") @RequestParam(
					required = false) List<AiJobStatus> status,
			@Parameter(description = "Only these types (repeatable)") @RequestParam(required = false) List<AiJobType> type,
			// No @PageableDefault: it would set its own size of 10 over the configured default of 20
			@SortDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		var page = jobs.list(userId, status == null ? List.of() : status, type == null ? List.of() : type,
				toEntitySort(pageable));
		return PageResponse.from(new PageImpl<>(page.getContent(), pageable, page.getTotalElements()),
				JobResponse::from);
	}

	@GetMapping("/progress")
	@Operation(summary = "Progress of several AI jobs",
			description = "Counts by status for up to 100 jobs, e.g. the batches of one bulk add. Poll until "
					+ "finished is true.")
	@ApiResponse(responseCode = "200", description = "The counts")
	Progress progress(@CurrentUserId UUID userId,
			@Parameter(description = "Job IDs (repeatable, 1–100)") @RequestParam(name = "id",
					required = false) List<UUID> ids) {
		return jobs.progress(userId, ids == null ? List.of() : ids);
	}

	private static Pageable toEntitySort(Pageable pageable) {
		List<Sort.Order> orders = pageable.getSort().stream().map(order -> {
			String property = SORTABLE.get(order.getProperty());
			if (property == null) {
				throw new InvalidFieldException("sort", "can't sort by '" + order.getProperty() + "'; use one of "
						+ SORTABLE.keySet().stream().sorted().collect(Collectors.joining(", ")));
			}
			return order.withProperty(property);
		}).toList();
		return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(orders).and(Sort.by("id")));
	}

}
