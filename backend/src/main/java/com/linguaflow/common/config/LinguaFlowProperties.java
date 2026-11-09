package com.linguaflow.common.config;

import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application-specific settings under the {@code linguaflow.*} prefix. Validated at
 * startup so a missing or weak secret fails fast instead of at first use.
 */
@Validated
@ConfigurationProperties("linguaflow")
public record LinguaFlowProperties(@Valid @NotNull Security security, @Valid @NotNull Ai ai, Cors cors,
		@Valid RateLimit rateLimit) {

	public LinguaFlowProperties {
		if (cors == null) {
			cors = new Cors(List.of());
		}
		if (rateLimit == null) {
			rateLimit = new RateLimit(null, null, null);
		}
	}

	public record Security(@Valid @NotNull Jwt jwt, RefreshCookie refreshCookie) {

		public Security {
			if (refreshCookie == null) {
				refreshCookie = new RefreshCookie(null, null, null);
			}
		}

	}

	/**
	 * Token settings. The secret signs access tokens with HS256 (32+ characters = 256+
	 * bits). Defaults: access tokens live 15 minutes, refresh tokens 30 days.
	 */
	public record Jwt(
			@NotBlank
			@Pattern(regexp = "^(?!\\$\\{).*", message = "is not set: provide the JWT_SECRET environment variable")
			@Size(min = 32, message = "must be at least 32 characters") String secret,
			String issuer, Duration accessTokenTtl, Duration refreshTokenTtl) {

		public Jwt {
			issuer = (issuer == null || issuer.isBlank()) ? "linguaflow" : issuer;
			accessTokenTtl = (accessTokenTtl == null) ? Duration.ofMinutes(15) : accessTokenTtl;
			refreshTokenTtl = (refreshTokenTtl == null) ? Duration.ofDays(30) : refreshTokenTtl;
		}

	}

	/**
	 * The refresh-token cookie. {@code secure} should only be false for plain-HTTP setups
	 * other than localhost (browsers accept Secure cookies on http://localhost).
	 */
	public record RefreshCookie(String name, Boolean secure, String sameSite) {

		public RefreshCookie {
			name = (name == null || name.isBlank()) ? "lf_refresh" : name;
			secure = (secure == null) ? Boolean.TRUE : secure;
			sameSite = (sameSite == null || sameSite.isBlank()) ? "Strict" : sameSite;
		}

	}

	/** {@code apiKey} may be blank: the app then uses the fake LLM client. */
	public record Ai(String apiKey) {

		public boolean hasApiKey() {
			return apiKey != null && !apiKey.isBlank();
		}

	}

	/** Browser origins allowed to call /api cross-origin; empty means same-origin only. */
	public record Cors(List<String> allowedOrigins) {

		public Cors {
			allowedOrigins = (allowedOrigins == null) ? List.of() : List.copyOf(allowedOrigins);
		}

	}

	/**
	 * Per-client-IP limits for the public auth endpoints (AUTH-06). Defaults: 10 logins per
	 * minute, 5 registrations per hour. Disabled in the test profile.
	 */
	public record RateLimit(Boolean enabled, @Valid Limit login, @Valid Limit register) {

		public RateLimit {
			enabled = (enabled == null) ? Boolean.TRUE : enabled;
			login = (login == null) ? new Limit(10, Duration.ofMinutes(1)) : login;
			register = (register == null) ? new Limit(5, Duration.ofHours(1)) : register;
		}

	}

	/** {@code requests} allowed per {@code period}, refilled gradually. */
	public record Limit(@jakarta.validation.constraints.Positive int requests, @NotNull Duration period) {
	}

}
