package com.linguaflow.flashcard;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.linguaflow.common.persistence.AuditableEntity;
import com.linguaflow.common.persistence.OwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Where a flashcard stands in spaced repetition, one row per card. FC-01 creates it as
 * NEW with the SM-2 defaults; the scheduler (SRS-01) and the review queue (SRS-02) move
 * it on.
 */
@Entity
@Table(name = "srs_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SrsState extends AuditableEntity implements OwnedEntity {

	/** SM-2's starting ease. */
	public static final BigDecimal INITIAL_EASE = new BigDecimal("2.50");

	public enum State {

		NEW, LEARNING, REVIEW, RELEARNING

	}

	/** The card's ID: a shared primary key, set from the card. */
	@Id
	@Column(name = "flashcard_id")
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private State state = State.NEW;

	@Column(name = "ease_factor", nullable = false, precision = 4, scale = 2)
	private BigDecimal easeFactor = INITIAL_EASE;

	@Column(name = "interval_days", nullable = false)
	private int intervalDays;

	@Column(nullable = false)
	private int repetitions;

	@Column(nullable = false)
	private int lapses;

	/** Null while NEW. */
	@Column(name = "due_at")
	private @Nullable Instant dueAt;

	@Column(name = "last_reviewed_at")
	private @Nullable Instant lastReviewedAt;

	/** A wrapper, so Spring Data sees a new row (null) and persists rather than merges. */
	@Version
	@Column(nullable = false)
	private Long version;

	SrsState(Flashcard card) {
		this.id = Objects.requireNonNull(card.getId(), "save the card first");
		this.userId = card.getUserId();
	}

}
