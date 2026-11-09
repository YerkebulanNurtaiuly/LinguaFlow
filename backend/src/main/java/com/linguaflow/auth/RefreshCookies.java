package com.linguaflow.auth;

import java.time.Duration;

import com.linguaflow.common.config.LinguaFlowProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds the refresh-token cookie: HttpOnly (no JavaScript access), Secure, SameSite
 * (Strict by default: never sent on cross-site requests, which is what protects the
 * cookie-authenticated endpoints from CSRF) and scoped to /api/auth.
 */
@Component
class RefreshCookies {

	static final String PATH = "/api/auth";

	private final LinguaFlowProperties.RefreshCookie settings;

	private final Duration ttl;

	RefreshCookies(LinguaFlowProperties properties, RefreshTokenService refreshTokens) {
		this.settings = properties.security().refreshCookie();
		this.ttl = refreshTokens.ttl();
	}

	String name() {
		return settings.name();
	}

	ResponseCookie create(String refreshToken) {
		return base(refreshToken).maxAge(ttl).build();
	}

	ResponseCookie clear() {
		return base("").maxAge(Duration.ZERO).build();
	}

	private ResponseCookie.ResponseCookieBuilder base(String value) {
		return ResponseCookie.from(settings.name(), value)
			.httpOnly(true)
			.secure(settings.secure())
			.sameSite(settings.sameSite())
			.path(PATH);
	}

}
