package com.linguaflow.exercise;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedEntity;
import com.linguaflow.exercise.Evaluation.EvaluatedBy;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One answer to an exercise and how it was graded (EX-05). Append-only: the history
 * behind accuracy and weak points (PRG-01, PRG-03).
 */
@Entity
@Table(name = "exercise_attempts")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExerciseAttempt implements OwnedEntity {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "exercise_id", nullable = false, updatable = false)
	private UUID exerciseId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	/** Set once practice sessions (PRC-02) record their attempts. */
	@Column(name = "practice_session_id", updatable = false)
	private @Nullable UUID practiceSessionId;

	/** The {@link ExerciseAnswer} as JSON. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, updatable = false)
	private Map<String, Object> answer;

	@Column(nullable = false, updatable = false)
	private boolean correct;

	@Column(nullable = false, updatable = false, precision = 4, scale = 3)
	private BigDecimal score;

	@Enumerated(EnumType.STRING)
	@Column(name = "evaluated_by", nullable = false, updatable = false)
	private EvaluatedBy evaluatedBy;

	/** The AI judge's findings (EX-04); null when the rules decided. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(updatable = false)
	private Evaluation.@Nullable Feedback feedback;

	@Column(name = "attempted_at", nullable = false, updatable = false)
	private Instant attemptedAt;

	@Column(name = "duration_ms", updatable = false)
	private @Nullable Integer durationMs;

	/** @throws IllegalArgumentException for an evaluation that didn't decide */
	public ExerciseAttempt(Exercise exercise, ExerciseAnswer answer, Evaluation evaluation, Instant attemptedAt,
			@Nullable Integer durationMs) {
		if (!evaluation.decided()) {
			throw new IllegalArgumentException("Only a decided evaluation can be stored");
		}
		this.exerciseId = Objects.requireNonNull(exercise.getId());
		this.userId = exercise.getUserId();
		this.answer = ExerciseContent.toJson(answer);
		this.correct = evaluation.correct();
		this.score = BigDecimal.valueOf(evaluation.score()).setScale(3, RoundingMode.HALF_UP);
		this.evaluatedBy = evaluation.evaluatedBy();
		this.feedback = evaluation.feedback();
		this.attemptedAt = Objects.requireNonNull(attemptedAt);
		this.durationMs = durationMs;
	}

}
