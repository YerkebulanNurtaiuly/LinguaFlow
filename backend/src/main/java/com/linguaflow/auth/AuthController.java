package com.linguaflow.auth;

import java.net.URI;
import java.time.Clock;

import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ErrorCode;
import com.linguaflow.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Registration, login and session tokens")
@SecurityRequirements // all auth endpoints are public; refresh/logout use the refresh-token cookie
class AuthController {

	private static final String COOKIE = "${linguaflow.security.refresh-cookie.name:lf_refresh}";

	private final RegistrationService registrationService;

	private final AuthService authService;

	private final RefreshCookies cookies;

	private final Clock clock;

	AuthController(RegistrationService registrationService, AuthService authService, RefreshCookies cookies,
			Clock clock) {
		this.registrationService = registrationService;
		this.authService = authService;
		this.cookies = cookies;
		this.clock = clock;
	}

	@PostMapping("/register")
	@Operation(summary = "Create an account", description = "Does not log the user in; call /api/auth/login next.")
	@ApiResponse(responseCode = "201", description = "Account created")
	@ApiResponse(responseCode = "409", description = "Email already registered (code EMAIL_TAKEN)")
	ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegistrationRequest request) {
		User user = registrationService.register(request);
		return ResponseEntity.created(URI.create("/api/users/me")).body(RegistrationResponse.from(user));
	}

	@PostMapping("/login")
	@Operation(summary = "Log in", description = "Returns a short-lived access token and sets the HttpOnly "
			+ "refresh-token cookie (path /api/auth).")
	@ApiResponse(responseCode = "200", description = "Logged in")
	@ApiResponse(responseCode = "401", description = "Wrong email or password (code INVALID_CREDENTIALS)")
	@ApiResponse(responseCode = "403", description = "Account disabled (code ACCOUNT_DISABLED)")
	ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
		return withTokens(authService.login(request, userAgent));
	}

	@PostMapping("/refresh")
	@Operation(summary = "Get a new access token",
			description = "Uses and rotates the refresh-token cookie. Reusing an old refresh token revokes the "
					+ "whole login session.")
	@Parameter(name = "lf_refresh", in = ParameterIn.COOKIE, description = "Refresh token (set by login)")
	@ApiResponse(responseCode = "200", description = "New access token; cookie rotated")
	@ApiResponse(responseCode = "401", description = "Missing, expired, revoked or reused refresh token "
			+ "(code INVALID_REFRESH_TOKEN)")
	ResponseEntity<TokenResponse> refresh(@CookieValue(name = COOKIE, required = false) String refreshToken,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
			HttpServletResponse response) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN, "No refresh token; log in again");
		}
		try {
			return withTokens(authService.refresh(refreshToken, userAgent));
		}
		catch (ApiException ex) {
			// Drop the dead cookie so the client stops sending it
			response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear().toString());
			throw ex;
		}
	}

	@PostMapping("/logout")
	@Operation(summary = "Log out", description = "Revokes the refresh-token session and clears the cookie. "
			+ "Idempotent.")
	@Parameter(name = "lf_refresh", in = ParameterIn.COOKIE, description = "Refresh token (set by login)")
	@ApiResponse(responseCode = "204", description = "Logged out")
	ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String refreshToken) {
		if (refreshToken != null && !refreshToken.isBlank()) {
			authService.logout(refreshToken);
		}
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear().toString()).build();
	}

	private ResponseEntity<TokenResponse> withTokens(AuthService.Tokens tokens) {
		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, cookies.create(tokens.refreshToken()).toString())
			.body(TokenResponse.bearer(tokens.accessToken(), clock.instant()));
	}

}
