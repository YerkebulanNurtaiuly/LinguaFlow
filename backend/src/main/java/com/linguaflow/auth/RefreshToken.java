package com.linguaflow.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * One issued refresh token, stored only as a SHA-256 hash. All tokens descending from one
 * login share a {@code familyId}; presenting a token that was already rotated (or revoked)
 * means it leaked, so the whole family is revoked.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "token_hash", nullable = false, updatable = false)
	private String tokenHash;

	@Column(name = "family_id", nullable = false, updatable = false)
	private UUID familyId;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "replaced_by_id")
	private UUID replacedById;

	@Column(name = "user_agent", updatable = false)
	private String userAgent;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	RefreshToken(UUID userId, String tokenHash, UUID familyId, Instant createdAt, Instant expiresAt,
			String userAgent) {
		this.userId = Objects.requireNonNull(userId);
		this.tokenHash = Objects.requireNonNull(tokenHash);
		this.familyId = Objects.requireNonNull(familyId);
		this.createdAt = Objects.requireNonNull(createdAt);
		this.expiresAt = Objects.requireNonNull(expiresAt);
		this.userAgent = userAgent;
	}

	boolean isUsable(Instant now) {
		return revokedAt == null && replacedById == null && now.isBefore(expiresAt);
	}

	/** True if this token was already exchanged or revoked: presenting it again is reuse. */
	boolean isSpent() {
		return revokedAt != null || replacedById != null;
	}

	void replaceWith(RefreshToken successor, Instant now) {
		this.replacedById = successor.getId();
		this.revokedAt = now;
	}

}
