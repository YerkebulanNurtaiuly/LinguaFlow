package com.linguaflow.learning.practice;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One practice run (PRC-02) in a learning language: flashcards and exercises chosen by
 * mode. It starts IN_PROGRESS; completing it stores a {@link PracticeSummary}. Its items
 * live in {@code practice_session_items} ({@link PracticeItems}).
 */
@Entity
@Table(name = "practice_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PracticeSession extends AuditableEntity implements OwnedEntity {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "language_code", nullable = false, updatable = false)
	private String languageCode;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private PracticeMode mode;

	/** CURRENT_LESSON: the lesson practised; a plain ID, cleared if the lesson is deleted. */
	@Column(name = "study_session_id", updatable = false)
	private @Nullable UUID studySessionId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, updatable = false)
	private PracticeConfig config;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PracticeSessionStatus status;

	@Column(name = "started_at", nullable = false, updatable = false)
	private Instant startedAt;

	@Column(name = "completed_at")
	private @Nullable Instant completedAt;

	@JdbcTypeCode(SqlTypes.JSON)
	private @Nullable PracticeSummary summary;

	@Version
	@Column(nullable = false)
	private long version;

	public PracticeSession(UUID userId, String languageCode, PracticeMode mode, @Nullable UUID studySessionId,
			PracticeConfig config, Instant startedAt) {
		this.userId = Objects.requireNonNull(userId);
		this.languageCode = Objects.requireNonNull(languageCode);
		this.mode = Objects.requireNonNull(mode);
		this.studySessionId = studySessionId;
		this.config = Objects.requireNonNull(config);
		this.startedAt = Objects.requireNonNull(startedAt);
		this.status = PracticeSessionStatus.IN_PROGRESS;
	}

	/** @throws IllegalStateException unless it's in progress */
	public void complete(PracticeSummary summary, Instant at) {
		if (status != PracticeSessionStatus.IN_PROGRESS) {
			throw new IllegalStateException("The session is already " + status);
		}
		this.summary = Objects.requireNonNull(summary);
		this.completedAt = Objects.requireNonNull(at);
		this.status = PracticeSessionStatus.COMPLETED;
	}

	public boolean inProgress() {
		return status == PracticeSessionStatus.IN_PROGRESS;
	}

}
