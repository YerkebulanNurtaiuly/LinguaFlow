package com.linguaflow.exercise;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.common.security.CurrentUserId;
import com.linguaflow.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.SortDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Exercise sets written by the AI for chosen grammar topics (EX-02). */
@RestController
@RequestMapping("/api/exercise-sets")
@Tag(name = "Exercises", description = "Grammar exercises written for your topics and words")
public class ExerciseSetController {

	private final ExerciseSetService service;

	ExerciseSetController(ExerciseSetService service) {
		this.service = service;
	}

	@Schema(name = "CreateExerciseSetRequest")
	record CreateRequest(
			@Schema(description = "1 to 5 of your grammar topics in the active language") @NotNull @Size(min = 1,
					max = ExerciseSetService.MAX_TOPICS) List<@NotNull UUID> grammarTopicIds,
			@Schema(description = "Exercise formats; empty or absent means your preferred formats, or else all") @Nullable List<@NotNull ExerciseType> types,
			@Schema(description = "How many exercises; defaults to 10", example = "10") @Nullable @Min(1) @Max(
					ExerciseSetService.MAX_COUNT) Integer count,
			@Nullable ExerciseDifficulty difficulty) {
	}

	@Schema(name = "ExerciseSetRequest", description = "What was asked for")
	record RequestResponse(List<UUID> grammarTopicIds, List<ExerciseType> types, int count,
			ExerciseDifficulty difficulty,
			@Schema(description = "Words practised first; set by practice sessions, empty otherwise") List<UUID> vocabularyItemIds) {

		static RequestResponse from(ExerciseSetRequest request) {
			return new RequestResponse(request.grammarTopicIds(), request.types(), request.count(),
					request.difficulty(), request.vocabularyItemIds());
		}

	}

	@Schema(name = "ExerciseSetSummary")
	record SummaryResponse(UUID id, String languageCode, ExerciseSetSource source,
			@Schema(description = "GENERATING until the AI is done, then READY or FAILED") ExerciseSetStatus status,
			RequestResponse request,
			@Schema(description = "May be fewer than requested when some generated exercises failed the checks") long exerciseCount,
			Instant createdAt) {

		static SummaryResponse from(ExerciseSetService.SetSummary summary) {
			ExerciseSet set = summary.set();
			return new SummaryResponse(set.getId(), set.getLanguageCode(), set.getSource(), set.getStatus(),
					RequestResponse.from(set.getRequest()), summary.exerciseCount(), set.getCreatedAt());
		}

	}

	@Schema(name = "ExerciseSetCreated")
	record CreatedResponse(ExerciseSetResponse exerciseSet,
			@Schema(description = "Follow it with GET /api/ai/jobs/{id}, then reload the set") UUID aiJobId) {
	}

	@Schema(name = "ExerciseSet")
	record ExerciseSetResponse(UUID id, String languageCode, ExerciseSetSource source, ExerciseSetStatus status,
			RequestResponse request, List<ExerciseResponse> exercises, Instant createdAt) {

		static ExerciseSetResponse from(ExerciseSet set, List<Exercise> exercises) {
			return new ExerciseSetResponse(set.getId(), set.getLanguageCode(), set.getSource(), set.getStatus(),
					RequestResponse.from(set.getRequest()), exercises.stream().map(ExerciseResponse::from).toList(),
					set.getCreatedAt());
		}

	}

	@Schema(name = "Exercise", description = "One exercise, without its solution")
	public record ExerciseResponse(UUID id, int position, ExerciseType type, ExerciseDifficulty difficulty,
			@Schema(description = "Its shape depends on the type",
					oneOf = { ExercisePrompt.FillBlank.class, ExercisePrompt.MultipleChoice.class,
							ExercisePrompt.Translation.class, ExercisePrompt.SentenceConstruction.class,
							ExercisePrompt.ErrorCorrection.class, ExercisePrompt.WordOrder.class,
							ExercisePrompt.Transformation.class }) ExercisePrompt prompt,
			Set<UUID> grammarTopicIds, Set<UUID> vocabularyItemIds,
			@Schema(description = "Reported as a bad exercise") boolean reported) {

		public static ExerciseResponse from(Exercise exercise) {
			return new ExerciseResponse(exercise.getId(), exercise.getPosition(), exercise.getType(),
					exercise.getDifficulty(), exercise.content().prompt(), exercise.getGrammarTopicIds(),
					exercise.getVocabularyItemIds(), exercise.isReported());
		}

	}

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Operation(summary = "Ask for an exercise set",
			description = "Saves the set as GENERATING and queues the AI. It practises the chosen topics with your own "
					+ "words; each exercise is checked, and bad ones are replaced. Follow the job, then reload the set.")
	@ApiResponse(responseCode = "202", description = "The set, still generating, and its job")
	CreatedResponse create(@CurrentUserId UUID userId, @Valid @RequestBody CreateRequest request) {
		var requested = service.create(userId, new ExerciseSetService.NewExerciseSet(request.grammarTopicIds(),
				request.types(), request.count(), request.difficulty()));
		return new CreatedResponse(ExerciseSetResponse.from(requested.set(), List.of()), requested.aiJobId());
	}

	@GetMapping
	@Operation(summary = "List exercise sets", description = "The active language's sets, newest first.")
	@ApiResponse(responseCode = "200", description = "One page of sets")
	PageResponse<SummaryResponse> list(@CurrentUserId UUID userId,
			@SortDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		Sort sort = Sort.unsorted();
		for (Sort.Order order : pageable.getSort()) {
			if (!order.getProperty().equals("createdAt")) {
				throw new InvalidFieldException("sort", "can't sort by '" + order.getProperty() + "'; use createdAt");
			}
			sort = sort.and(Sort.by(order));
		}
		var page = service.list(userId,
				PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort.and(Sort.by("id"))));
		return PageResponse.from(new PageImpl<>(page.getContent(), pageable, page.getTotalElements()),
				SummaryResponse::from);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get an exercise set", description = "With its exercises in order, without solutions.")
	@ApiResponse(responseCode = "200", description = "The set")
	ExerciseSetResponse get(@CurrentUserId UUID userId, @PathVariable UUID id) {
		var found = service.get(userId, id);
		return ExerciseSetResponse.from(found.set(), found.exercises());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Delete an exercise set", description = "With its exercises.")
	@ApiResponse(responseCode = "204", description = "Deleted")
	void delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
		service.delete(userId, id);
	}

}
