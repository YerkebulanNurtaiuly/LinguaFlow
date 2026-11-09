package com.linguaflow.auth;

import java.time.Clock;

import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ErrorCode;
import com.linguaflow.user.User;
import com.linguaflow.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Login and refresh: verifies credentials and hands out access + refresh tokens. */
@Service
public class AuthService {

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final AccessTokenService accessTokens;

	private final RefreshTokenService refreshTokens;

	private final Clock clock;

	// Compared against when the email is unknown, so both failure paths take the same time
	private final String dummyHash;

	AuthService(UserRepository users, PasswordEncoder passwordEncoder, AccessTokenService accessTokens,
			RefreshTokenService refreshTokens, Clock clock) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.accessTokens = accessTokens;
		this.refreshTokens = refreshTokens;
		this.clock = clock;
		this.dummyHash = passwordEncoder.encode("timing-equalizer-not-a-real-password");
	}

	/**
	 * @throws ApiException INVALID_CREDENTIALS (unknown email or wrong password, same
	 * response for both) or ACCOUNT_DISABLED
	 */
	@Transactional
	public Tokens login(LoginRequest request, String userAgent) {
		User user = users.findByEmail(request.email().strip()).orElse(null);
		boolean passwordMatches = passwordEncoder.matches(request.password(),
				user != null ? user.getPasswordHash() : dummyHash);
		if (user == null || !passwordMatches) {
			throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Email or password is incorrect");
		}
		if (!user.isEnabled()) {
			throw new ApiException(ErrorCode.ACCOUNT_DISABLED, "This account has been disabled");
		}
		user.recordLogin(clock.instant());
		return startSession(user, userAgent);
	}

	/** A new login session (refresh-token family) plus access token for an authenticated user. */
	Tokens startSession(User user, String userAgent) {
		return new Tokens(accessTokens.issue(user), refreshTokens.issue(user.getId(), userAgent));
	}

	/** Rotates the refresh token and issues a fresh access token. */
	@Transactional(noRollbackFor = ApiException.class)
	public Tokens refresh(String refreshToken, String userAgent) {
		RefreshTokenService.Rotation rotation = refreshTokens.rotate(refreshToken, userAgent);
		User user = users.findById(rotation.userId())
			.filter(User::isEnabled)
			.orElseThrow(() -> new ApiException(ErrorCode.INVALID_REFRESH_TOKEN,
					"Refresh token is invalid or expired; log in again"));
		return new Tokens(accessTokens.issue(user), rotation.refreshToken());
	}

	public void logout(String refreshToken) {
		refreshTokens.revokeFamilyOf(refreshToken);
	}

	public record Tokens(AccessTokenService.IssuedAccessToken accessToken, String refreshToken) {
	}

}
