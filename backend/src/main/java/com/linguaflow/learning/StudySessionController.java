package com.linguaflow.learning;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.linguaflow.common.error.ApiProblem;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.common.security.CurrentUserId;
import com.linguaflow.common.web.PageResponse;
import com.linguaflow.learning.StudySessionService.NewSession;
import com.linguaflow.learning.StudySessionService.SessionChanges;
import com.linguaflow.learning.StudySessionService.SessionMaterial;
import com.linguaflow.learning.StudySessionService.SessionSummary;
import com.linguaflow.learning.StudySessionService.Timeline;
import com.linguaflow.learning.StudySessionService.TimelineMonth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.SortDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Study sessions: lessons with the words and grammar topics studied in them (SES-01). */
@RestController
@RequestMapping("/api/study-sessions")
@Tag(name = "Study sessions", description = "Your lessons and the material you studied in each")
class StudySessionController {

	private static final Map<String, String> SORTABLE = Map.of("studiedOn", "studiedOn", "createdAt", "createdAt",
			"updatedAt", "updatedAt", "title", "title");

	private final StudySessionService sessions;

	StudySessionController(StudySessionService sessions) {
		this.sessions = sessions;
	}

	@Schema(name = "CreateStudySessionRequest")
	record CreateRequest(
			@Schema(example = "Lesson 5") @NotBlank @Size(max = StudySessionService.MAX_TITLE_LENGTH) String title,
			@Schema(description = "Defaults to today in your time zone") LocalDate studiedOn,
			@Size(max = StudySessionService.MAX_NOTES_LENGTH) String notes,
			@Schema(description = "Make it the active session, so new words and topics join it; ends any "
					+ "other active session") Boolean active) {
	}

	@Schema(name = "UpdateStudySessionRequest",
			description = "Partial update: absent or null fields stay unchanged; an empty string clears the notes")
	record UpdateRequest(
			@Size(min = 1, max = StudySessionService.MAX_TITLE_LENGTH)
			@Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") String title,
			LocalDate studiedOn, @Size(max = StudySessionService.MAX_NOTES_LENGTH) String notes,
			@Schema(description = "true makes it the active session (ending any other); false ends it")
			Boolean active) {
	}

	@Schema(name = "AttachVocabularyRequest")
	record AttachVocabularyRequest(
			@NotEmpty @Size(max = StudySessionService.MAX_ATTACH) List<@NotNull UUID> vocabularyItemIds) {
	}

	@Schema(name = "AttachGrammarRequest")
	record AttachGrammarRequest(
			@NotEmpty @Size(max = StudySessionService.MAX_ATTACH) List<@NotNull UUID> grammarTopicIds) {
	}

	@Schema(name = "StudySession")
	record SessionResponse(UUID id, @Schema(example = "Lesson 5") String title, LocalDate studiedOn, String notes,
			@Schema(description = "New words and topics join the active session") boolean active,
			long vocabularyCount, long grammarTopicCount, Instant createdAt, Instant updatedAt, long version) {

		static SessionResponse from(SessionSummary summary) {
			StudySession s = summary.session();
			return new SessionResponse(s.getId(), s.getTitle(), s.getStudiedOn(), s.getNotes(), s.isActive(),
					summary.vocabularyCount(), summary.grammarTopicCount(), s.getCreatedAt(), s.getUpdatedAt(),
					s.getVersion());
		}

	}

	@Schema(name = "StudySessionTimelineMonth")
	record TimelineMonthResponse(@Schema(example = "2026-11", description = "Year and month, yyyy-MM") String month,
			int sessionCount, long vocabularyCount, long grammarTopicCount,
			@Schema(description = "Most recent lesson first") List<SessionResponse> sessions) {

		static TimelineMonthResponse from(TimelineMonth month) {
			return new TimelineMonthResponse(month.month().toString(), month.sessions().size(),
					month.vocabularyCount(), month.grammarTopicCount(),
					month.sessions().stream().map(SessionResponse::from).toList());
		}

	}

	@Schema(name = "StudySessionTimeline",
			description = "Counts are attachments: a word studied in two lessons counts in both")
	record TimelineResponse(@Schema(description = "Most recent month first") List<TimelineMonthResponse> months,
			int sessionCount, long vocabularyCount, long grammarTopicCount) {

		static TimelineResponse from(Timeline timeline) {
			return new TimelineResponse(timeline.months().stream().map(TimelineMonthResponse::from).toList(),
					timeline.sessionCount(), timeline.vocabularyCount(), timeline.grammarTopicCount());
		}

	}

	@Schema(name = "StudySessionMaterial", description = "IDs of the session's material, in the order it was added")
	record MaterialResponse(List<UUID> vocabularyItemIds, List<UUID> grammarTopicIds) {

		static MaterialResponse from(SessionMaterial material) {
			return new MaterialResponse(material.vocabularyItemIds(), material.grammarTopicIds());
		}

	}

	@GetMapping
	@Operation(summary = "List study sessions",
			description = "Sessions of the active language, most recent lesson first. Sortable by studiedOn, "
					+ "createdAt, updatedAt, title.")
	@ApiResponse(responseCode = "200", description = "One page of sessions")
	@ApiResponse(responseCode = "409", description = "No active language yet (code NO_ACTIVE_LANGUAGE)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	PageResponse<SessionResponse> list(@CurrentUserId UUID userId,
			@PageableDefault @SortDefault.SortDefaults({
					@SortDefault(sort = "studiedOn", direction = Sort.Direction.DESC),
					@SortDefault(sort = "createdAt", direction = Sort.Direction.DESC) }) Pageable pageable) {
		var page = sessions.list(userId, toEntitySort(pageable));
		return PageResponse.from(new PageImpl<>(page.getContent(), pageable, page.getTotalElements()),
				SessionResponse::from);
	}

	@GetMapping("/timeline")
	@Operation(summary = "Learning history",
			description = "Your lessons in the active language grouped by month, most recent first, with how many "
					+ "words and grammar topics each covered. Optionally limited to lessons studied from and/or to a "
					+ "date (inclusive).")
	@ApiResponse(responseCode = "200", description = "The timeline")
	@ApiResponse(responseCode = "409", description = "No active language yet (code NO_ACTIVE_LANGUAGE)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	TimelineResponse timeline(@CurrentUserId UUID userId,
			@Parameter(description = "First day, e.g. 2026-10-01") @RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@Parameter(description = "Last day, e.g. 2026-10-31") @RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return TimelineResponse.from(sessions.timeline(userId, from, to));
	}

	@GetMapping("/active")
	@Operation(summary = "The active study session",
			description = "The session new words and topics join; 204 when none is active.")
	@ApiResponse(responseCode = "200", description = "The active session")
	@ApiResponse(responseCode = "204", description = "No session is active")
	ResponseEntity<SessionResponse> active(@CurrentUserId UUID userId) {
		return sessions.active(userId)
			.map(summary -> ResponseEntity.ok(SessionResponse.from(summary)))
			.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@PostMapping
	@Operation(summary = "Create a study session", description = "Created in the active language.")
	@ApiResponse(responseCode = "201", description = "Session created")
	ResponseEntity<SessionResponse> create(@CurrentUserId UUID userId, @Valid @RequestBody CreateRequest request) {
		SessionSummary created = sessions.create(userId, new NewSession(request.title(), request.studiedOn(),
				request.notes(), Boolean.TRUE.equals(request.active())));
		return ResponseEntity.created(URI.create("/api/study-sessions/" + created.session().getId()))
			.body(SessionResponse.from(created));
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get one study session")
	SessionResponse get(@CurrentUserId UUID userId, @PathVariable UUID id) {
		return SessionResponse.from(sessions.get(userId, id));
	}

	@PatchMapping("/{id}")
	@Operation(summary = "Edit a study session")
	@ApiResponse(responseCode = "200", description = "Session updated")
	@ApiResponse(responseCode = "409", description = "The session was changed meanwhile (code CONFLICT)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	SessionResponse update(@CurrentUserId UUID userId, @PathVariable UUID id,
			@Valid @RequestBody UpdateRequest request) {
		return SessionResponse.from(sessions.update(userId, id,
				new SessionChanges(request.title(), request.studiedOn(), request.notes(), request.active())));
	}

	@DeleteMapping("/{id}")
	@Operation(summary = "Delete a study session", description = "Its words and topics stay in your collection.")
	@ApiResponse(responseCode = "204", description = "Deleted")
	ResponseEntity<Void> delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
		sessions.delete(userId, id);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/{id}/material")
	@Operation(summary = "What a study session covers",
			description = "IDs of its words and topics, e.g. for Current Lesson practice. For the full words and "
					+ "topics, filter the lists by sessionId.")
	MaterialResponse material(@CurrentUserId UUID userId, @PathVariable UUID id) {
		return MaterialResponse.from(sessions.material(userId, id));
	}

	@PostMapping("/{id}/vocabulary")
	@Operation(summary = "Attach words", description = "Words already attached are skipped.")
	@ApiResponse(responseCode = "200", description = "The session with its new counts")
	SessionResponse attachVocabulary(@CurrentUserId UUID userId, @PathVariable UUID id,
			@Valid @RequestBody AttachVocabularyRequest request) {
		return SessionResponse.from(sessions.attachVocabulary(userId, id, request.vocabularyItemIds()));
	}

	@DeleteMapping("/{id}/vocabulary/{vocabularyItemId}")
	@Operation(summary = "Detach a word", description = "The word itself stays.")
	@ApiResponse(responseCode = "204", description = "Detached")
	ResponseEntity<Void> detachVocabulary(@CurrentUserId UUID userId, @PathVariable UUID id,
			@PathVariable UUID vocabularyItemId) {
		sessions.detachVocabulary(userId, id, vocabularyItemId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/grammar-topics")
	@Operation(summary = "Attach grammar topics", description = "Topics already attached are skipped.")
	@ApiResponse(responseCode = "200", description = "The session with its new counts")
	SessionResponse attachGrammar(@CurrentUserId UUID userId, @PathVariable UUID id,
			@Valid @RequestBody AttachGrammarRequest request) {
		return SessionResponse.from(sessions.attachGrammar(userId, id, request.grammarTopicIds()));
	}

	@DeleteMapping("/{id}/grammar-topics/{grammarTopicId}")
	@Operation(summary = "Detach a grammar topic", description = "The topic itself stays.")
	@ApiResponse(responseCode = "204", description = "Detached")
	ResponseEntity<Void> detachGrammar(@CurrentUserId UUID userId, @PathVariable UUID id,
			@PathVariable UUID grammarTopicId) {
		sessions.detachGrammar(userId, id, grammarTopicId);
		return ResponseEntity.noContent().build();
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
		return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
				Sort.by(orders).and(Sort.by("id")));
	}

}
