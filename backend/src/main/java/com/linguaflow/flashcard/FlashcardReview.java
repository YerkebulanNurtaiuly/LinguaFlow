package com.linguaflow.flashcard;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

/**
 * One graded review of a flashcard (SRS-02). Append-only: the history behind streaks,
 * statistics (SRS-03) and progress. The client's {@code reviewToken} makes a submission
 * idempotent: the same token for the same card is stored once.
 */
@Entity
@Table(name = "flashcard_reviews")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FlashcardReview implements OwnedEntity {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "flashcard_id", nullable = false, updatable = false)
	private UUID flashcardId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	/** Set once practice sessions (PRC-02) record their reviews. */
	@Column(name = "practice_session_id", updatable = false)
	private @Nullable UUID practiceSessionId;

	@Column(name = "review_token", nullable = false, updatable = false)
	private UUID reviewToken;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private ReviewGrade grade;

	@Column(name = "reviewed_at", nullable = false, updatable = false)
	private Instant reviewedAt;

	@Column(name = "duration_ms", updatable = false)
	private @Nullable Integer durationMs;

	@Column(name = "interval_before", nullable = false, updatable = false)
	private int intervalBefore;

	@Column(name = "interval_after", nullable = false, updatable = false)
	private int intervalAfter;

	@Column(name = "ease_before", nullable = false, updatable = false, precision = 4, scale = 2)
	private BigDecimal easeBefore;

	@Column(name = "ease_after", nullable = false, updatable = false, precision = 4, scale = 2)
	private BigDecimal easeAfter;

	FlashcardReview(Flashcard card, UUID reviewToken, ReviewGrade grade, Instant reviewedAt,
			@Nullable Integer durationMs, SrsScheduler.Card before, SrsScheduler.Card after) {
		this.flashcardId = Objects.requireNonNull(card.getId());
		this.userId = card.getUserId();
		this.reviewToken = Objects.requireNonNull(reviewToken);
		this.grade = Objects.requireNonNull(grade);
		this.reviewedAt = Objects.requireNonNull(reviewedAt);
		this.durationMs = durationMs;
		this.intervalBefore = before.intervalDays();
		this.intervalAfter = after.intervalDays();
		this.easeBefore = before.ease();
		this.easeAfter = after.ease();
	}

}
