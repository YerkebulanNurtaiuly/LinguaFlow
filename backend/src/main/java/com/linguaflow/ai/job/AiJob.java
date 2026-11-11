package com.linguaflow.ai.job;

import java.time.Instant;
import java.util.Map;
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
 * One unit of background AI work in the DB-backed queue (AI-04), so work survives a
 * restart. The state changes live here; {@link AiJobWorker} decides when to make them.
 * <p>
 * {@code userId} is null for system jobs. User jobs are deleted with the user.
 */
@Entity
@Table(name = "ai_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiJob extends AuditableEntity implements OwnedEntity {

	/** {@code last_error} is cut to this length. */
	static final int MAX_ERROR_LENGTH = 1_000;

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "user_id", updatable = false)
	private @Nullable UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private AiJobType type;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AiJobStatus status;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, updatable = false)
	private Map<String, Object> payload;

	@JdbcTypeCode(SqlTypes.JSON)
	private @Nullable Map<String, Object> result;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "max_attempts", nullable = false)
	private int maxAttempts;

	@Column(name = "next_run_at", nullable = false)
	private Instant nextRunAt;

	@Column(name = "locked_at")
	private @Nullable Instant lockedAt;

	@Column(name = "locked_by")
	private @Nullable String lockedBy;

	@Column(name = "last_error")
	private @Nullable String lastError;

	AiJob(AiJobType type, @Nullable UUID userId, Map<String, Object> payload, int maxAttempts, Instant now) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be at least 1");
		}
		this.type = type;
		this.userId = userId;
		this.payload = Map.copyOf(payload);
		this.maxAttempts = maxAttempts;
		this.status = AiJobStatus.QUEUED;
		this.nextRunAt = now;
	}

	/** Claimed by {@code workerId}: one more attempt starts. */
	void start(String workerId, Instant now) {
		requireStatus(AiJobStatus.QUEUED);
		status = AiJobStatus.RUNNING;
		attempts++;
		lockedAt = now;
		lockedBy = workerId;
	}

	/** Whether {@code workerId} still holds this job, so its outcome may be recorded. */
	boolean heldBy(String workerId) {
		return status == AiJobStatus.RUNNING && workerId.equals(lockedBy);
	}

	boolean attemptsLeft() {
		return attempts < maxAttempts;
	}

	void succeed(@Nullable Map<String, Object> result) {
		requireStatus(AiJobStatus.RUNNING);
		this.result = result == null ? null : Map.copyOf(result);
		status = AiJobStatus.SUCCEEDED;
		lastError = null;
		unlock();
	}

	/** A retryable failure: queued again for {@code nextRunAt}. */
	void retryAt(Instant nextRunAt, String error) {
		requireStatus(AiJobStatus.RUNNING);
		status = AiJobStatus.QUEUED;
		this.nextRunAt = nextRunAt;
		lastError = cut(error);
		unlock();
	}

	/** A failure retrying can't fix. */
	void fail(String error) {
		end(AiJobStatus.FAILED, error);
	}

	/** Every attempt failed: dead-lettered. */
	void deadLetter(String error) {
		end(AiJobStatus.DEAD, error);
	}

	private void end(AiJobStatus finalStatus, String error) {
		requireStatus(AiJobStatus.RUNNING);
		status = finalStatus;
		lastError = cut(error);
		unlock();
	}

	private void unlock() {
		lockedAt = null;
		lockedBy = null;
	}

	private void requireStatus(AiJobStatus expected) {
		if (status != expected) {
			throw new IllegalStateException("AI job " + id + " is " + status + ", not " + expected);
		}
	}

	private static String cut(String error) {
		String text = error == null || error.isBlank() ? "Unknown error" : error.strip();
		return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH - 1) + "…" : text;
	}

}
