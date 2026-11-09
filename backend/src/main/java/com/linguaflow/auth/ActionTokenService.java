package com.linguaflow.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.linguaflow.common.config.LinguaFlowProperties;
import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ErrorCode;
import com.linguaflow.common.security.SecureTokens;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Issues and redeems the single-use tokens behind emailed links. */
@Service
class ActionTokenService {

	private final UserActionTokenRepository tokens;

	private final Clock clock;

	private final LinguaFlowProperties.ActionTokens settings;

	ActionTokenService(UserActionTokenRepository tokens, Clock clock, LinguaFlowProperties properties) {
		this.tokens = tokens;
		this.clock = clock;
		this.settings = properties.security().actionTokens();
	}

	/** Returns the raw token for the link. Older unused tokens of the same purpose stop working. */
	@Transactional
	String issue(UUID userId, ActionTokenPurpose purpose) {
		tokens.deleteUnused(userId, purpose);
		String raw = SecureTokens.generate();
		Instant now = clock.instant();
		tokens.save(new UserActionToken(userId, purpose, SecureTokens.hash(raw), now, now.plus(ttl(purpose))));
		return raw;
	}

	/**
	 * Marks the token used and returns its user. Must run inside the caller's transaction so
	 * the action and the "used" mark commit together.
	 * @throws ApiException INVALID_TOKEN for unknown, used or expired tokens
	 */
	// noRollbackFor: an inner method that throws would otherwise mark the caller's whole
	// transaction rollback-only, and the caller's commit would fail with UnexpectedRollbackException
	@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiException.class)
	UUID redeem(String rawToken, ActionTokenPurpose purpose) {
		UserActionToken token = tokens.findForUpdate(SecureTokens.hash(rawToken), purpose)
			.orElseThrow(ActionTokenService::invalid);
		Instant now = clock.instant();
		if (!token.isUsable(now)) {
			throw invalid();
		}
		token.markUsed(now);
		return token.getUserId();
	}

	Duration ttl(ActionTokenPurpose purpose) {
		return switch (purpose) {
			case EMAIL_VERIFICATION -> settings.emailVerificationTtl();
			case PASSWORD_RESET -> settings.passwordResetTtl();
		};
	}

	private static ApiException invalid() {
		return new ApiException(ErrorCode.INVALID_TOKEN, "This link is invalid, expired or already used");
	}

}
