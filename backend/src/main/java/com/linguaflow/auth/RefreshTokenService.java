package com.linguaflow.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import com.linguaflow.common.config.LinguaFlowProperties;
import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates and revokes opaque refresh tokens. The raw token only ever exists in the
 * client's cookie; the database stores its SHA-256 hash.
 */
@Service
public class RefreshTokenService {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

	private static final int TOKEN_BYTES = 32;

	private static final int MAX_USER_AGENT_LENGTH = 300;

	private final SecureRandom random = new SecureRandom();

	private final RefreshTokenRepository tokens;

	private final Clock clock;

	private final Duration ttl;

	RefreshTokenService(RefreshTokenRepository tokens, Clock clock, LinguaFlowProperties properties) {
		this.tokens = tokens;
		this.clock = clock;
		this.ttl = properties.security().jwt().refreshTokenTtl();
	}

	public Duration ttl() {
		return ttl;
	}

	/** Starts a new token family (a new login). */
	@Transactional
	public String issue(UUID userId, String userAgent) {
		return create(userId, UUID.randomUUID(), userAgent).raw();
	}

	/**
	 * Exchanges a valid token for a new one in the same family. A token that was already
	 * exchanged or revoked is treated as stolen: the whole family is revoked. The
	 * revocation must commit even though the call fails, hence {@code noRollbackFor}.
	 * @throws ApiException INVALID_REFRESH_TOKEN for unknown, expired, revoked or reused tokens
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public Rotation rotate(String rawToken, String userAgent) {
		RefreshToken current = tokens.findByTokenHashForUpdate(hash(rawToken)).orElseThrow(this::invalid);
		Instant now = clock.instant();
		if (current.isSpent()) {
			int revoked = tokens.revokeFamily(current.getFamilyId(), now);
			log.warn("Refresh token reuse detected for user {} (family {}); revoked {} token(s)", current.getUserId(),
					current.getFamilyId(), revoked);
			throw invalid();
		}
		if (!current.isUsable(now)) {
			throw invalid(); // expired
		}
		Created next = create(current.getUserId(), current.getFamilyId(), userAgent);
		current.replaceWith(next.token(), now);
		return new Rotation(current.getUserId(), next.raw());
	}

	/** Logout: revokes the token's whole family. Unknown tokens are ignored (idempotent). */
	@Transactional
	public void revokeFamilyOf(String rawToken) {
		tokens.findByTokenHashForUpdate(hash(rawToken))
			.ifPresent(token -> tokens.revokeFamily(token.getFamilyId(), clock.instant()));
	}

	private Created create(UUID userId, UUID familyId, String userAgent) {
		byte[] bytes = new byte[TOKEN_BYTES];
		random.nextBytes(bytes);
		String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant now = clock.instant();
		RefreshToken token = tokens.save(new RefreshToken(userId, hash(raw), familyId, now, now.plus(ttl),
				truncate(userAgent)));
		return new Created(token, raw);
	}

	static String hash(String rawToken) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}

	private static String truncate(String userAgent) {
		if (userAgent == null) {
			return null;
		}
		return userAgent.length() <= MAX_USER_AGENT_LENGTH ? userAgent : userAgent.substring(0, MAX_USER_AGENT_LENGTH);
	}

	private ApiException invalid() {
		return new ApiException(ErrorCode.INVALID_REFRESH_TOKEN, "Refresh token is invalid or expired; log in again");
	}

	public record Rotation(UUID userId, String refreshToken) {
	}

	private record Created(RefreshToken token, String raw) {
	}

}
