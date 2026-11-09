package com.linguaflow.auth;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/** Access token for the Authorization header. The refresh token travels only in its cookie. */
public record TokenResponse(String accessToken, @Schema(example = "Bearer") String tokenType,
		@Schema(description = "Lifetime in seconds", example = "900") long expiresIn, Instant expiresAt) {

	static TokenResponse bearer(AccessTokenService.IssuedAccessToken token, Instant now) {
		return new TokenResponse(token.value(), "Bearer", token.expiresAt().getEpochSecond() - now.getEpochSecond(),
				token.expiresAt());
	}

}
