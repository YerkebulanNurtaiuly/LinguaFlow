package com.linguaflow.auth;

import java.time.Clock;
import java.util.UUID;

import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ErrorCode;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.common.error.NotFoundException;
import com.linguaflow.user.User;
import com.linguaflow.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Email verification and password reset (AUTH-07). */
@Service
public class AccountService {

	private static final Logger log = LoggerFactory.getLogger(AccountService.class);

	private final UserRepository users;

	private final ActionTokenService actionTokens;

	private final AccountEmails emails;

	private final PasswordEncoder passwordEncoder;

	private final RefreshTokenService refreshTokens;

	private final AuthService authService;

	private final Clock clock;

	AccountService(UserRepository users, ActionTokenService actionTokens, AccountEmails emails,
			PasswordEncoder passwordEncoder, RefreshTokenService refreshTokens, AuthService authService, Clock clock) {
		this.users = users;
		this.actionTokens = actionTokens;
		this.emails = emails;
		this.passwordEncoder = passwordEncoder;
		this.refreshTokens = refreshTokens;
		this.authService = authService;
		this.clock = clock;
	}

	/** Sends a fresh verification link; a no-op if the email is already verified. */
	public void sendVerificationEmail(UUID userId) {
		User user = users.findById(userId).orElseThrow(() -> new NotFoundException("Resource not found"));
		if (user.getEmailVerifiedAt() != null) {
			return;
		}
		String token = actionTokens.issue(user.getId(), ActionTokenPurpose.EMAIL_VERIFICATION);
		emails.sendVerification(user, token, actionTokens.ttl(ActionTokenPurpose.EMAIL_VERIFICATION));
	}

	@Transactional(noRollbackFor = ApiException.class)
	public void verifyEmail(String token) {
		UUID userId = actionTokens.redeem(token, ActionTokenPurpose.EMAIL_VERIFICATION);
		User user = users.findById(userId).orElseThrow(AccountService::invalidLink);
		if (user.getEmailVerifiedAt() == null) {
			user.markEmailVerified(clock.instant());
		}
	}

	/**
	 * Emails a reset link if an enabled account has this address. Callers always answer the
	 * same way, so the response does not reveal whether the email is registered.
	 */
	public void requestPasswordReset(String email) {
		users.findByEmail(email.strip()).filter(User::isEnabled).ifPresent(user -> {
			String token = actionTokens.issue(user.getId(), ActionTokenPurpose.PASSWORD_RESET);
			emails.sendPasswordReset(user, token, actionTokens.ttl(ActionTokenPurpose.PASSWORD_RESET));
		});
	}

	/** Sets the new password, burns the link and logs the user out on every device. */
	@Transactional(noRollbackFor = ApiException.class)
	public void resetPassword(String token, String newPassword) {
		UUID userId = actionTokens.redeem(token, ActionTokenPurpose.PASSWORD_RESET);
		User user = users.findById(userId).filter(User::isEnabled).orElseThrow(AccountService::invalidLink);
		user.changePasswordHash(passwordEncoder.encode(newPassword));
		int sessions = refreshTokens.revokeAllForUser(userId);
		log.info("Password reset for user {}; revoked {} session token(s)", userId, sessions);
	}

	private static ApiException invalidLink() {
		return new ApiException(ErrorCode.INVALID_TOKEN, "This link is invalid, expired or already used");
	}

	/**
	 * Changes the password after checking the current one. Every existing session is
	 * revoked (other devices are logged out); the caller gets a fresh session so it stays
	 * logged in.
	 * @throws InvalidFieldException currentPassword incorrect, or newPassword unchanged
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public AuthService.Tokens changePassword(UUID userId, String currentPassword, String newPassword,
			String userAgent) {
		User user = existingUser(userId);
		requireCurrentPassword(user, currentPassword, "currentPassword");
		if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
			throw new InvalidFieldException("newPassword", "must differ from the current password");
		}
		user.changePasswordHash(passwordEncoder.encode(newPassword));
		int revoked = refreshTokens.revokeAllForUser(userId);
		log.info("Password changed for user {}; revoked {} session token(s)", userId, revoked);
		return authService.startSession(user, userAgent);
	}

	/**
	 * Permanently deletes the account and, through the database cascades, all its data
	 * (SEC-02). Requires the password so a stolen access token alone cannot do it.
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public void deleteAccount(UUID userId, String password) {
		User user = existingUser(userId);
		requireCurrentPassword(user, password, "password");
		users.delete(user);
		log.info("Deleted user {} and all their data", userId);
	}

	private void requireCurrentPassword(User user, String password, String field) {
		if (!passwordEncoder.matches(password, user.getPasswordHash())) {
			throw new InvalidFieldException(field, "is incorrect");
		}
	}

	private User existingUser(UUID userId) {
		return users.findById(userId)
			.orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "This account no longer exists"));
	}

}
