package com.linguaflow.exercise;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.common.security.CurrentUserId;
import com.linguaflow.common.web.PageResponse;
import com.linguaflow.exercise.Evaluation.EvaluatedBy;
import com.linguaflow.exercise.Evaluation.Note;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
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

/** Answering exercises (EX-05). */
@RestController
@RequestMapping("/api/exercises/{id}/attempts")
@Tag(name = "Exercises", description = "Grammar exercises written for your topics and words")
class ExerciseAttemptController {

	/** Longest attempt time kept: an hour. */
	static final int MAX_DURATION_MS = 3_600_000;

	private final AttemptService attempts;

	ExerciseAttemptController(AttemptService attempts) {
		this.attempts = attempts;
	}

	@Schema(name = "SubmitAttemptRequest",
			description = "Fill in the field the exercise type takes: blanks for FILL_BLANK, index for "
					+ "MULTIPLE_CHOICE, tokens for WORD_ORDER, text for the others")
	record SubmitRequest(
			@Schema(description = "What was typed into each gap, in order") @Nullable @Size(
					max = 10) List<@Size(max = 200) String> blanks,
			@Schema(description = "Zero-based index of the chosen option") @Nullable @Min(0) @Max(10) Integer index,
			@Schema(description = "The prompt's tokens in the chosen order") @Nullable @Size(
					max = 30) List<@Size(max = 100) String> tokens,
			@Schema(description = "The written answer") @Nullable @Size(max = 1000) String text,
			@Schema(description = "How long answering took") @Nullable @Min(0) @Max(MAX_DURATION_MS) Integer durationMs) {

		/** The answer in the shape {@code type} takes. */
		ExerciseAnswer answerFor(ExerciseType type) {
			Class<? extends ExerciseAnswer> shape = ExerciseAnswer.typeFor(type);
			if (shape == ExerciseAnswer.Blanks.class) {
				return new ExerciseAnswer.Blanks(require("blanks", blanks));
			}
			if (shape == ExerciseAnswer.Choice.class) {
				return new ExerciseAnswer.Choice(require("index", index));
			}
			if (shape == ExerciseAnswer.Tokens.class) {
				return new ExerciseAnswer.Tokens(require("tokens", tokens));
			}
			return new ExerciseAnswer.Text(require("text", text));
		}

		private static <T> T require(String field, @Nullable T value) {
			if (value == null) {
				throw new InvalidFieldException(field, "is required for this exercise type");
			}
			return value;
		}

	}

	@Schema(name = "ExerciseAttempt")
	record AttemptResponse(UUID id, UUID exerciseId,
			@Schema(description = "The answer as given: {blanks}, {index}, {tokens} or {text}") Map<String, Object> answer,
			boolean correct, @Schema(description = "0 to 1; partial credit for gaps and word order") BigDecimal score,
			EvaluatedBy evaluatedBy, Instant attemptedAt, Integer durationMs) {

		static AttemptResponse from(ExerciseAttempt attempt) {
			return new AttemptResponse(attempt.getId(), attempt.getExerciseId(), attempt.getAnswer(),
					attempt.isCorrect(), attempt.getScore(), attempt.getEvaluatedBy(), attempt.getAttemptedAt(),
					attempt.getDurationMs());
		}

	}

	@Schema(name = "ExerciseAttemptResult", description = "How the answer was graded, with the solution")
	record ResultResponse(AttemptResponse attempt, boolean correct, double score, EvaluatedBy evaluatedBy,
			@Schema(description = "What was accepted although it differed") Set<Note> notes,
			@Schema(description = "Per gap, or per answer token, whether it was right") List<Boolean> parts,
			@Schema(description = "The solution, e.g. the text with its gaps filled") String correctAnswer,
			@Schema(description = "Other accepted answers") List<String> alternatives,
			@Schema(description = "Why the solution is right, written with the exercise") String explanation,
			@Schema(description = "The AI judge's findings; null when the rules decided") FeedbackResponse feedback,
			List<MistakeResponse> mistakes) {

		static ResultResponse from(AttemptService.Result result) {
			Evaluation evaluation = result.evaluation();
			return new ResultResponse(AttemptResponse.from(result.attempt()), evaluation.correct(), evaluation.score(),
					evaluation.evaluatedBy(), evaluation.notes(), evaluation.parts(), evaluation.correctAnswer(),
					alternatives(result.content().solution()), result.content().solution().explanation(),
					FeedbackResponse.from(evaluation.feedback()),
					result.mistakes().stream().map(MistakeResponse::from).toList());
		}

		private static List<String> alternatives(ExerciseSolution solution) {
			return switch (solution) {
				case ExerciseSolution.TextAnswer text -> text.alternatives();
				case ExerciseSolution.TokenOrder order ->
					order.alternatives().stream().map(tokens -> String.join(" ", tokens)).toList();
				case ExerciseSolution.Blanks blanks -> List.of();
				case ExerciseSolution.Choice choice -> List.of();
			};
		}

	}

	@Schema(name = "ExerciseFeedback")
	record FeedbackResponse(List<ErrorResponse> errors,
			@Schema(description = "Your answer corrected; null when it was right") String correctedAnswer,
			@Schema(description = "A sentence or two for you") String explanation) {

		static @Nullable FeedbackResponse from(Evaluation.@Nullable Feedback feedback) {
			return feedback == null ? null
					: new FeedbackResponse(feedback.errors().stream().map(ErrorResponse::from).toList(),
							feedback.correctedAnswer(), feedback.explanation());
		}

	}

	@Schema(name = "AnswerError")
	record ErrorResponse(ErrorType type, @Schema(description = "The wrong part; null for something missing") String span,
			String explanation, @Schema(description = "Your grammar topic it belongs to") UUID grammarTopicId) {

		static ErrorResponse from(Evaluation.AnswerError error) {
			return new ErrorResponse(error.type(), error.span(), error.explanation(), error.grammarTopicId());
		}

	}

	@Schema(name = "Mistake", description = "A stored error, for your mistake statistics")
	record MistakeResponse(UUID id,
			@Schema(description = "An AnswerError type, or WRONG_GAP / WRONG_CHOICE / WORD_ORDER from the rules") String errorType,
			String excerpt, UUID grammarTopicId) {

		static MistakeResponse from(Mistake mistake) {
			return new MistakeResponse(mistake.getId(), mistake.getErrorType(), mistake.getExcerpt(),
					mistake.getGrammarTopicId());
		}

	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Answer an exercise",
			description = "Grades the answer, by rules or for open answers by the AI, and stores the attempt with "
					+ "its mistakes. The response has the correct answer and explanations. Nothing is stored when "
					+ "the AI fails (429 AI_QUOTA_EXCEEDED or 503 AI_UNAVAILABLE).")
	@ApiResponse(responseCode = "201", description = "The graded attempt")
	ResultResponse submit(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody SubmitRequest request) {
		ExerciseType type = attempts.typeOf(userId, id);
		return ResultResponse.from(attempts.submit(userId, id, request.answerFor(type), request.durationMs()));
	}

	@GetMapping
	@Operation(summary = "An exercise's attempts", description = "Newest first; sortable by attemptedAt only.")
	@ApiResponse(responseCode = "200", description = "One page of attempts")
	PageResponse<AttemptResponse> history(@CurrentUserId UUID userId, @PathVariable UUID id,
			@SortDefault(sort = "attemptedAt", direction = Sort.Direction.DESC) Pageable pageable) {
		Sort sort = Sort.unsorted();
		for (Sort.Order order : pageable.getSort()) {
			if (!order.getProperty().equals("attemptedAt")) {
				throw new InvalidFieldException("sort", "can't sort by '" + order.getProperty() + "'; use attemptedAt");
			}
			sort = sort.and(Sort.by(order));
		}
		var page = attempts.history(userId, id,
				PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort.and(Sort.by(Sort.Direction.DESC, "id"))));
		return PageResponse.from(new PageImpl<>(page.getContent(), pageable, page.getTotalElements()),
				AttemptResponse::from);
	}

}
