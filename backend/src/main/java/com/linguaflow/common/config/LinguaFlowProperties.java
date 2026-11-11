package com.linguaflow.common.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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
public record LinguaFlowProperties(@Valid @NotNull Security security, Ai ai, Cors cors,
		@Valid RateLimit rateLimit, App app, Mail mail) {

	public LinguaFlowProperties {
		if (ai == null) {
			ai = new Ai(null, null, null, null, null);
		}
		if (app == null) {
			app = new App(null);
		}
		if (mail == null) {
			mail = new Mail(null, null);
		}
		if (cors == null) {
			cors = new Cors(List.of());
		}
		if (rateLimit == null) {
			rateLimit = new RateLimit(null, null, null, null);
		}
	}

	public record Security(@Valid @NotNull Jwt jwt, RefreshCookie refreshCookie, ActionTokens actionTokens) {

		public Security {
			if (refreshCookie == null) {
				refreshCookie = new RefreshCookie(null, null, null);
			}
			if (actionTokens == null) {
				actionTokens = new ActionTokens(null, null);
			}
		}

	}

	/** Lifetimes of emailed links (AUTH-07). Defaults: verification 48 h, password reset 1 h. */
	public record ActionTokens(Duration emailVerificationTtl, Duration passwordResetTtl) {

		public ActionTokens {
			emailVerificationTtl = (emailVerificationTtl == null) ? Duration.ofHours(48) : emailVerificationTtl;
			passwordResetTtl = (passwordResetTtl == null) ? Duration.ofHours(1) : passwordResetTtl;
		}

	}

	/** Public URL of the frontend, used to build links in emails. */
	public record App(String frontendUrl) {

		public App {
			frontendUrl = (frontendUrl == null || frontendUrl.isBlank()) ? "http://localhost:5173"
					: frontendUrl.replaceAll("/+$", "");
		}

	}

	/**
	 * Outgoing mail. {@code mode}: {@code log} writes emails (including links) to the log,
	 * for dev/test only; {@code disabled} (the default) sends nothing and logs nothing
	 * sensitive. A real SMTP mode can be added later.
	 */
	public record Mail(String mode, String from) {

		public Mail {
			mode = (mode == null || mode.isBlank()) ? "disabled" : mode;
			from = (from == null || from.isBlank()) ? "LinguaFlow <no-reply@linguaflow.local>" : from;
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

	/**
	 * The LLM provider (AI-01).
	 * @param apiKey Anthropic API key (env {@code AI_API_KEY}); a secret, never logged
	 * @param provider {@code auto} (the default) uses Anthropic when a key is set and the
	 * fake client otherwise; {@code fake} always uses the fake; {@code anthropic} requires a
	 * key and fails at startup without one
	 * @param model the model for every use case without its own
	 * @param useCases per use case (keys like {@code vocabulary-enrichment}): model,
	 * effort, max-tokens and timeout, each optional
	 * @param baseUrl the API endpoint; always set explicitly, so an {@code ANTHROPIC_BASE_URL}
	 * in the environment can't redirect calls
	 */
	public record Ai(String apiKey, Provider provider, String model, Map<String, UseCase> useCases,
			String baseUrl) {

		public static final String DEFAULT_MODEL = "claude-opus-5-5";

		public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

		public enum Provider {

			AUTO, FAKE, ANTHROPIC

		}

		/** Overrides for one use case; null fields keep the built-in default. */
		public record UseCase(String model, String effort, Integer maxTokens, Duration timeout) {
		}

		public Ai {
			apiKey = (apiKey == null) ? "" : apiKey.strip();
			provider = (provider == null) ? Provider.AUTO : provider;
			model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model.strip();
			useCases = (useCases == null) ? Map.of() : Map.copyOf(useCases);
			baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.strip();
			if (provider == Provider.ANTHROPIC && apiKey.isEmpty()) {
				throw new IllegalArgumentException(
						"linguaflow.ai.provider is anthropic but no API key is set (env AI_API_KEY)");
			}
		}

		public boolean hasApiKey() {
			return !apiKey.isEmpty();
		}

		/** Whether calls go to Anthropic rather than the fake client. */
		public boolean usesAnthropic() {
			return provider == Provider.ANTHROPIC || (provider == Provider.AUTO && hasApiKey());
		}

		/** Keeps the key out of logs and error messages. */
		@Override
		public String toString() {
			return "Ai[apiKey=" + (hasApiKey() ? "***" : "") + ", provider=" + provider + ", model=" + model
					+ ", useCases=" + useCases + ", baseUrl=" + baseUrl + "]";
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
	 * minute, 5 registrations and 5 password-reset requests per hour. Disabled in the test profile.
	 */
	public record RateLimit(Boolean enabled, @Valid Limit login, @Valid Limit register, @Valid Limit passwordReset) {

		public RateLimit {
			enabled = (enabled == null) ? Boolean.TRUE : enabled;
			login = (login == null) ? new Limit(10, Duration.ofMinutes(1)) : login;
			register = (register == null) ? new Limit(5, Duration.ofHours(1)) : register;
			passwordReset = (passwordReset == null) ? new Limit(5, Duration.ofHours(1)) : passwordReset;
		}

	}

	/** {@code requests} allowed per {@code period}, refilled gradually. */
	public record Limit(@jakarta.validation.constraints.Positive int requests, @NotNull Duration period) {
	}

}
