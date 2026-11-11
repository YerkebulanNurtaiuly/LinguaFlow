package com.linguaflow.common.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
public record LinguaFlowProperties(@Valid @NotNull Security security, @Valid Ai ai, Cors cors,
		@Valid RateLimit rateLimit, App app, Mail mail) {

	public LinguaFlowProperties {
		if (ai == null) {
			ai = new Ai(null, null, null, null, null, null, null, null, null);
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
	 * @param jobs the background job queue (AI-04)
	 * @param quota the per-user daily AI limits (AI-05)
	 * @param circuitBreaker when to stop calling a failing provider for a while (AI-05)
	 * @param pricing USD per million tokens by model ID, for cost estimates; added to and
	 * overriding the built-in prices (AI-05)
	 */
	public record Ai(String apiKey, Provider provider, String model, Map<String, UseCase> useCases,
			String baseUrl, @Valid Jobs jobs, @Valid Quota quota, @Valid Breaker circuitBreaker,
			Map<String, @Valid Price> pricing) {

		public static final String DEFAULT_MODEL = "claude-opus-5-5";

		public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

		public enum Provider {

			AUTO, FAKE, ANTHROPIC

		}

		/** Overrides for one use case; null fields keep the built-in default. */
		public record UseCase(String model, String effort, Integer maxTokens, Duration timeout) {
		}

		/**
		 * The DB-backed AI job queue (AI-04). Null fields keep the defaults.
		 * @param enabled whether this instance runs a worker (off in the test profile, where
		 * tests drive it)
		 * @param pollInterval how often the worker looks for due jobs
		 * @param concurrency how many jobs run at once on this instance
		 * @param batchSize how many items one job handles, e.g. words per enrichment call
		 * @param maxAttempts how many tries a job gets before it is dead-lettered
		 * @param backoffBase the wait before the first retry; it doubles per attempt
		 * @param backoffMax the longest wait between retries
		 * @param staleAfter a job RUNNING this long belongs to a crashed worker and is
		 * queued again
		 */
		public record Jobs(Boolean enabled, Duration pollInterval, @Min(1) @Max(64) Integer concurrency,
				@Min(1) @Max(200) Integer batchSize, @Min(1) @Max(20) Integer maxAttempts, Duration backoffBase,
				Duration backoffMax, Duration staleAfter) {

			public Jobs {
				enabled = (enabled == null) ? Boolean.TRUE : enabled;
				pollInterval = (pollInterval == null) ? Duration.ofSeconds(2) : pollInterval;
				concurrency = (concurrency == null) ? 4 : concurrency;
				batchSize = (batchSize == null) ? 20 : batchSize;
				maxAttempts = (maxAttempts == null) ? 3 : maxAttempts;
				backoffBase = (backoffBase == null) ? Duration.ofSeconds(30) : backoffBase;
				backoffMax = (backoffMax == null) ? Duration.ofHours(1) : backoffMax;
				staleAfter = (staleAfter == null) ? Duration.ofMinutes(10) : staleAfter;
			}

		}

		/**
		 * Per-user daily AI limits (AI-05), counted over the UTC day from {@code ai_requests}.
		 * System calls (no user) are not limited.
		 * @param enabled whether the limits apply
		 * @param dailyRequests LLM calls per user per day; 20 words count as one call
		 * @param dailyTokens input plus output tokens per user per day
		 */
		public record Quota(Boolean enabled, @Min(1) Integer dailyRequests, @Min(1) Long dailyTokens) {

			public Quota {
				enabled = (enabled == null) ? Boolean.TRUE : enabled;
				dailyRequests = (dailyRequests == null) ? 300 : dailyRequests;
				dailyTokens = (dailyTokens == null) ? 1_500_000L : dailyTokens;
			}

		}

		/**
		 * The circuit breaker around the provider (AI-05). Only timeouts, overload and
		 * network errors count as failures.
		 * @param failureRateThreshold percentage of failed calls that opens the circuit
		 * @param windowSize how many recent calls the rate is computed over
		 * @param minimumCalls calls needed before the rate is judged
		 * @param openFor how long calls are refused once open, before a few test calls
		 */
		public record Breaker(@Min(1) @Max(100) Integer failureRateThreshold, @Min(1) Integer windowSize,
				@Min(1) Integer minimumCalls, Duration openFor) {

			public Breaker {
				failureRateThreshold = (failureRateThreshold == null) ? 50 : failureRateThreshold;
				windowSize = (windowSize == null) ? 20 : windowSize;
				minimumCalls = (minimumCalls == null) ? 10 : minimumCalls;
				openFor = (openFor == null) ? Duration.ofSeconds(60) : openFor;
			}

		}

		/** USD per million tokens of each kind, as on the provider's pricing page. */
		public record Price(@NotNull BigDecimal input, @NotNull BigDecimal cacheWrite, @NotNull BigDecimal cacheRead,
				@NotNull BigDecimal output) {
		}

		public Ai {
			apiKey = (apiKey == null) ? "" : apiKey.strip();
			provider = (provider == null) ? Provider.AUTO : provider;
			model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model.strip();
			useCases = (useCases == null) ? Map.of() : Map.copyOf(useCases);
			baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.strip();
			jobs = (jobs == null) ? new Jobs(null, null, null, null, null, null, null, null) : jobs;
			quota = (quota == null) ? new Quota(null, null, null) : quota;
			circuitBreaker = (circuitBreaker == null) ? new Breaker(null, null, null, null) : circuitBreaker;
			pricing = (pricing == null) ? Map.of() : Map.copyOf(pricing);
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
					+ ", useCases=" + useCases + ", baseUrl=" + baseUrl + ", jobs=" + jobs + ", quota=" + quota + ", circuitBreaker=" + circuitBreaker
					+ ", pricing=" + pricing + "]";
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
