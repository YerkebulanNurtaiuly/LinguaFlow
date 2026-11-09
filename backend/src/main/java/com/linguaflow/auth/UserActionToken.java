package com.linguaflow.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/** Single-use, expiring token behind an emailed link; stored as a SHA-256 hash. */
@Entity
@Table(name = "user_action_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserActionToken {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private ActionTokenPurpose purpose;

	@Column(name = "token_hash", nullable = false, updatable = false)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	UserActionToken(UUID userId, ActionTokenPurpose purpose, String tokenHash, Instant createdAt, Instant expiresAt) {
		this.userId = Objects.requireNonNull(userId);
		this.purpose = Objects.requireNonNull(purpose);
		this.tokenHash = Objects.requireNonNull(tokenHash);
		this.createdAt = Objects.requireNonNull(createdAt);
		this.expiresAt = Objects.requireNonNull(expiresAt);
	}

	boolean isUsable(Instant now) {
		return usedAt == null && now.isBefore(expiresAt);
	}

	void markUsed(Instant now) {
		this.usedAt = now;
	}

}
