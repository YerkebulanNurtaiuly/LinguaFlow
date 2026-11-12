package com.linguaflow.learning.practice;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.common.security.CurrentUserId;
import com.linguaflow.common.web.PageResponse;
import com.linguaflow.exercise.ExerciseSetController.ExerciseResponse;
import com.linguaflow.exercise.ExerciseSetStatus;
import com.linguaflow.flashcard.ReviewController.QueueItemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.SortDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Practice sessions (PRC-02): flashcards and exercises chosen by mode, in one run. */
@RestController
@RequestMapping("/api/practice-sessions")
@Tag(name = "Practice", description = "Practice runs mixing flashcards and exercises")
class PracticeSessionController {

	private final PracticeSessionService service;

	PracticeSessionController(PracticeSessionService service) {
		this.service = service;
	}

	@Schema(name = "StartPracticeSessionRequest")
	record StartRequest(@NotNull PracticeMode mode,
			@Schema(description = "CURRENT_LESSON only: the lesson to practise; absent means the latest") @Nullable UUID studySessionId,
			@Schema(description = "Items wanted, 5–200; absent means your practiceSessionLength setting", example = "20") @Nullable @Min(PracticeSessionService.MIN_LENGTH) @Max(PracticeSessionService.MAX_LENGTH) Integer length,
			@Schema(description = "Show flashcards; defaults to true") @Nullable Boolean includeFlashcards,
			@Schema(description = "Add AI-written exercises on the chosen grammar topics; defaults to true") @Nullable Boolean includeExercises,
			@Schema(description = "MIXED only: the share of fresh material, 0–1; defaults to 0.6", example = "0.6") @Nullable @DecimalMin("0") @DecimalMax("1") Double newRatio) {
	}

	@Schema(name = "PracticeSessionConfig", description = "How the session was set up")
	record ConfigResponse(int length, boolean includeFlashcards, boolean includeExercises, double newRatio,
			@Schema(description = "Makes the material selection reproducible") long seed) {

		static ConfigResponse from(PracticeConfig config) {
			return new ConfigResponse(config.length(), config.includeFlashcards(), config.includeExercises(),
					config.newRatio(), config.seed());
		}

	}

	@Schema(name = "PracticeSessionItem",
			description = "One step: exactly one of flashcard and exercise is set, matching type")
	record ItemResponse(int position, PracticeItemType type,
			@Schema(description = "When it was first answered in this session; null while open") Instant answeredAt,
			@Schema(description = "The card with where each grade would send it; grade it with POST /api/reviews and this session's ID") QueueItemResponse flashcard,
			@Schema(description = "The exercise, without its solution; answer it with POST /api/exercises/{id}/attempts and this session's ID") ExerciseResponse exercise) {
	}

	@Schema(name = "PracticeExerciseSet", description = "The exercise set written for the session")
	record ExerciseSetResponse(UUID id,
			@Schema(description = "GENERATING until the AI is done; the exercises join the items when READY") ExerciseSetStatus status,
			@Schema(description = "Exercises asked for") int requested) {
	}

	@Schema(name = "PracticeSession")
	record SessionResponse(UUID id, String languageCode, PracticeMode mode, PracticeSessionStatus status,
			@Schema(description = "CURRENT_LESSON: the lesson practised") UUID studySessionId, ConfigResponse config,
			Instant startedAt, Instant completedAt,
			@Schema(description = "In order; exercises are added, interleaved with the open cards, once written") List<ItemResponse> items,
			@Schema(description = "Null when the session has no exercises") ExerciseSetResponse exerciseSet,
			@Schema(description = "Set when the session is completed") PracticeSummary summary) {

		static SessionResponse from(PracticeSessionService.View view) {
			PracticeSession session = view.session();
			List<ItemResponse> items = view.items().stream().map(item -> {
				var card = item.flashcardId() == null ? null : view.cards().get(item.flashcardId());
				var exercise = item.exerciseId() == null ? null : view.exercises().get(item.exerciseId());
				return new ItemResponse(item.position(), item.type(), item.completedAt(),
						card == null ? null : QueueItemResponse.from(card),
						exercise == null ? null : ExerciseResponse.from(exercise));
			}).toList();
			var set = view.exerciseSet();
			return new SessionResponse(session.getId(), session.getLanguageCode(), session.getMode(),
					session.getStatus(), session.getStudySessionId(), ConfigResponse.from(session.getConfig()),
					session.getStartedAt(), session.getCompletedAt(), items,
					set == null ? null
							: new ExerciseSetResponse(set.set().getId(), set.set().getStatus(),
									set.set().getRequest().count()),
					session.getSummary());
		}

	}

	@Schema(name = "PracticeSessionStarted")
	record StartedResponse(SessionResponse practiceSession,
			@Schema(description = "The job writing the exercises; null without exercises") UUID aiJobId) {
	}

	@Schema(name = "PracticeSessionSummary", description = "A session in a list")
	record ListedResponse(UUID id, PracticeMode mode, PracticeSessionStatus status, UUID studySessionId,
			Instant startedAt, Instant completedAt, @Schema(description = "Items in the session") int items,
			@Schema(description = "Items answered") int answered) {

		static ListedResponse from(PracticeSessionService.Listed listed) {
			PracticeSession session = listed.session();
			return new ListedResponse(session.getId(), session.getMode(), session.getStatus(),
					session.getStudySessionId(), session.getStartedAt(), session.getCompletedAt(),
					listed.progress().items(), listed.progress().answered());
		}

	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Start a practice session",
			description = "Chooses words and grammar topics by mode, shows one flashcard per chosen word, and asks "
					+ "the AI for exercises on the chosen topics (a third of the session when both are included). "
					+ "Cards are available at once; the exercises join the items, interleaved, when the set is READY.")
	@ApiResponse(responseCode = "201", description = "The session and the job writing its exercises")
	StartedResponse start(@CurrentUserId UUID userId, @Valid @RequestBody StartRequest request) {
		var started = service.start(userId,
				new PracticeSessionService.NewPracticeSession(request.mode(), request.studySessionId(),
						request.length(), !Boolean.FALSE.equals(request.includeFlashcards()),
						!Boolean.FALSE.equals(request.includeExercises()), request.newRatio()));
		return new StartedResponse(SessionResponse.from(started.view()), started.aiJobId());
	}

	@GetMapping
	@Operation(summary = "List practice sessions", description = "The active language's sessions, newest first.")
	@ApiResponse(responseCode = "200", description = "One page of sessions")
	PageResponse<ListedResponse> list(@CurrentUserId UUID userId,
			@SortDefault(sort = "startedAt", direction = Sort.Direction.DESC) Pageable pageable) {
		Sort sort = Sort.unsorted();
		for (Sort.Order order : pageable.getSort()) {
			if (!order.getProperty().equals("startedAt")) {
				throw new InvalidFieldException("sort", "can't sort by '" + order.getProperty() + "'; use startedAt");
			}
			sort = sort.and(Sort.by(order));
		}
		var page = service.list(userId,
				PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort.and(Sort.by("id"))));
		return PageResponse.from(new PageImpl<>(page.getContent(), pageable, page.getTotalElements()),
				ListedResponse::from);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get a practice session", description = "With its items, cards and exercises in order.")
	@ApiResponse(responseCode = "200", description = "The session")
	SessionResponse get(@CurrentUserId UUID userId, @PathVariable UUID id) {
		return SessionResponse.from(service.get(userId, id));
	}

	@Schema(name = "PracticeLesson", description = "A lesson with the material it has")
	record LessonResponse(UUID id, String title, LocalDate studiedOn,
			@Schema(description = "Enriched words, which have cards to show") int words, int topics) {
	}

	@Schema(name = "PracticeOverview", description = "What each practice mode would use")
	record OverviewResponse(
			@Schema(description = "The latest lesson, which CURRENT_LESSON practises; null without any") LessonResponse currentLesson,
			@Schema(description = "Enriched words in the active language") int words,
			@Schema(description = "Of those, added in the last week or never practised: MIXED's new share") int freshWords,
			@Schema(description = "Grammar topics in the active language") int topics,
			@Schema(description = "Your default session length") int practiceSessionLength,
			@Schema(description = "The latest unfinished session, to continue; null if none") ListedResponse inProgress) {

		static OverviewResponse from(PracticeSessionService.Overview overview) {
			var lesson = overview.currentLesson();
			return new OverviewResponse(
					lesson == null ? null
							: new LessonResponse(lesson.id(), lesson.title(), lesson.studiedOn(), lesson.words(),
									lesson.topics()),
					overview.words(), overview.freshWords(), overview.topics(), overview.practiceSessionLength(),
					overview.inProgress() == null ? null : ListedResponse.from(overview.inProgress()));
		}

	}

	@GetMapping("/overview")
	@Operation(summary = "What there is to practise",
			description = "Per mode, for the active language: the latest lesson's material, all words and topics, "
					+ "and the session in progress, if any.")
	@ApiResponse(responseCode = "200", description = "The overview")
	OverviewResponse overview(@CurrentUserId UUID userId) {
		return OverviewResponse.from(service.overview(userId));
	}

	@PostMapping("/{id}/retry")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Practise a session's mistakes again",
			description = "Starts a new session, in the same mode, with the cards and exercises the completed session "
					+ "got wrong. No new exercises are written. 409 when the session isn't completed or nothing "
					+ "was missed.")
	@ApiResponse(responseCode = "201", description = "The new session")
	StartedResponse retry(@CurrentUserId UUID userId, @PathVariable UUID id) {
		var started = service.retry(userId, id);
		return new StartedResponse(SessionResponse.from(started.view()), started.aiJobId());
	}

	@PostMapping("/{id}/complete")
	@Operation(summary = "Complete a practice session",
			description = "Stores a summary of the reviews and attempts made in it. Completing it again returns "
					+ "it unchanged.")
	@ApiResponse(responseCode = "200", description = "The completed session with its summary")
	SessionResponse complete(@CurrentUserId UUID userId, @PathVariable UUID id) {
		return SessionResponse.from(service.complete(userId, id));
	}

}
