package com.linguaflow.flashcard;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.common.security.CurrentUserId;
import com.linguaflow.flashcard.FlashcardController.CardResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Daily flashcard review (SRS-02): what to review now, and grading a card. */
@RestController
@RequestMapping("/api/reviews")
@Tag(name = "Reviews", description = "Spaced-repetition review of your flashcards")
public class ReviewController {

	private final ReviewService reviews;

	ReviewController(ReviewService reviews) {
		this.reviews = reviews;
	}

	@Schema(name = "ReviewQueue")
	record QueueResponse(@Schema(description = "Cards to review now, in order") List<QueueItemResponse> cards,
			@Schema(description = "Every card due now, also beyond the limit") long dueCount,
			@Schema(description = "New cards still available today, within your daily limit") long newCount,
			@Schema(description = "Your today, in your time zone", example = "2026-11-11") LocalDate today,
			@Schema(description = "Reviews recorded today") long reviewedToday,
			@Schema(description = "Your daily review goal") int dailyReviewGoal,
			@Schema(description = "New cards seen for the first time today") long newCardsToday,
			@Schema(description = "Your daily limit of new cards") int newCardsPerDay,
			@Schema(description = "When a card in a learning step comes due later today; null if none") Instant nextLearningDueAt) {

		static QueueResponse from(ReviewService.Queue queue) {
			return new QueueResponse(queue.cards().stream().map(QueueItemResponse::from).toList(), queue.dueCount(),
					queue.newCount(), queue.today(), queue.reviewedToday(), queue.dailyReviewGoal(),
					queue.newCardsToday(), queue.newCardsPerDay(), queue.nextLearningDueAt());
		}

	}

	@Schema(name = "ReviewQueueItem")
	record QueueItemResponse(CardResponse card,
			@Schema(description = "Where each grade would send the card") GradePreviews previews) {

		static QueueItemResponse from(ReviewService.QueueItem item) {
			var previews = item.previews();
			return new QueueItemResponse(CardResponse.from(item.view()),
					new GradePreviews(GradePreview.from(previews.get(ReviewGrade.AGAIN)),
							GradePreview.from(previews.get(ReviewGrade.HARD)),
							GradePreview.from(previews.get(ReviewGrade.GOOD)),
							GradePreview.from(previews.get(ReviewGrade.EASY))));
		}

	}

	@Schema(name = "ReviewGradePreviews")
	record GradePreviews(GradePreview again, GradePreview hard, GradePreview good, GradePreview easy) {
	}

	@Schema(name = "ReviewGradePreview", description = "The card's next state if given this grade now")
	record GradePreview(SrsState.State state,
			@Schema(description = "Days; 0 while in a learning step") int intervalDays,
			@Schema(description = "When it would come back") Instant dueAt) {

		static GradePreview from(SrsScheduler.Card card) {
			return new GradePreview(card.state(), card.intervalDays(), card.dueAt());
		}

	}

	@Schema(name = "SubmitReviewRequest")
	record SubmitRequest(@NotNull UUID flashcardId, @NotNull ReviewGrade grade,
			@Schema(description = "Made by the client for each answer; resending it changes nothing") @NotNull UUID reviewToken,
			@Schema(description = "How long the answer took, in milliseconds") @PositiveOrZero @Max(3_600_000) @Nullable Integer durationMs) {
	}

	@Schema(name = "ReviewResult")
	record ResultResponse(ReviewResponse review, @Schema(description = "The card after the review") CardResponse card,
			@Schema(description = "True when this review token was already recorded; nothing changed") boolean replayed) {

		static ResultResponse from(ReviewService.Result result) {
			return new ResultResponse(ReviewResponse.from(result.review()), CardResponse.from(result.card()),
					result.replayed());
		}

	}

	@Schema(name = "FlashcardReview")
	record ReviewResponse(UUID id, UUID flashcardId, ReviewGrade grade, Instant reviewedAt, Integer durationMs,
			int intervalBefore, int intervalAfter, BigDecimal easeBefore, BigDecimal easeAfter) {

		static ReviewResponse from(FlashcardReview review) {
			return new ReviewResponse(review.getId(), review.getFlashcardId(), review.getGrade(),
					review.getReviewedAt(), review.getDurationMs(), review.getIntervalBefore(),
					review.getIntervalAfter(), review.getEaseBefore(), review.getEaseAfter());
		}

	}

	@GetMapping("/due")
	@Operation(summary = "Cards to review now",
			description = "The active language's cards due now and new cards within today's limit: overdue first, "
					+ "then the rest due today (the weakest first), then new cards. Suspended cards, directions you "
					+ "don't review and words without a translation are left out.")
	@ApiResponse(responseCode = "200", description = "The review queue")
	QueueResponse due(@CurrentUserId UUID userId,
			@Parameter(description = "At most this many cards, 1–" + ReviewService.MAX_LIMIT) @RequestParam(
					defaultValue = "50") int limit) {
		if (limit < 1 || limit > ReviewService.MAX_LIMIT) {
			throw new InvalidFieldException("limit", "must be between 1 and " + ReviewService.MAX_LIMIT);
		}
		return QueueResponse.from(reviews.queue(userId, limit));
	}

	@PostMapping
	@Operation(summary = "Grade a card",
			description = "Reschedules the card and records the review. Resending the same reviewToken for the "
					+ "same card changes nothing and returns the stored review with replayed: true.")
	@ApiResponse(responseCode = "201", description = "Recorded")
	@ApiResponse(responseCode = "200", description = "Already recorded with this token")
	ResponseEntity<ResultResponse> submit(@CurrentUserId UUID userId, @Valid @RequestBody SubmitRequest request) {
		ReviewService.Result result = reviews.submit(userId, new ReviewService.Submission(request.flashcardId(),
				request.grade(), request.reviewToken(), request.durationMs()));
		return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
			.body(ResultResponse.from(result));
	}

}
