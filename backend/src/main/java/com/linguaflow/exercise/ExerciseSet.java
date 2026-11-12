package com.linguaflow.exercise;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A batch of exercises generated together for one learning language (EX-01). It starts
 * GENERATING and becomes READY or FAILED once generation (EX-02) is done.
 */
@Entity
@Table(name = "exercise_sets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExerciseSet extends AuditableEntity implements OwnedEntity {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "language_code", nullable = false, updatable = false)
	private String languageCode;

	/** The practice session it was generated for (PRC-02); a plain ID. */
	@Column(name = "practice_session_id")
	private @Nullable UUID practiceSessionId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private ExerciseSetSource source;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, updatable = false)
	private ExerciseSetRequest request;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ExerciseSetStatus status;

	public ExerciseSet(UUID userId, String languageCode, ExerciseSetSource source, ExerciseSetRequest request,
			@Nullable UUID practiceSessionId) {
		this.userId = Objects.requireNonNull(userId);
		this.languageCode = Objects.requireNonNull(languageCode);
		this.source = Objects.requireNonNull(source);
		this.request = Objects.requireNonNull(request);
		this.practiceSessionId = practiceSessionId;
		this.status = ExerciseSetStatus.GENERATING;
	}

	public void markReady() {
		requireGenerating();
		status = ExerciseSetStatus.READY;
	}

	public void markFailed() {
		requireGenerating();
		status = ExerciseSetStatus.FAILED;
	}

	private void requireGenerating() {
		if (status != ExerciseSetStatus.GENERATING) {
			throw new IllegalStateException("The set has already finished generating (" + status + ")");
		}
	}

}
