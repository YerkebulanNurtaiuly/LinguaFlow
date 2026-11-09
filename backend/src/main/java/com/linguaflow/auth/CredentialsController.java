package com.linguaflow.auth;

import java.time.Clock;
import java.util.UUID;

import com.linguaflow.auth.password.ValidPassword;
import com.linguaflow.common.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Password change and account deletion for the logged-in user (USR-01). */
@RestController
@RequestMapping("/api/users/me")
@Tag(name = "Profile", description = "The current user's account")
class CredentialsController {

	private final AccountService accounts;

	private final RefreshCookies cookies;

	private final Clock clock;

	CredentialsController(AccountService accounts, RefreshCookies cookies, Clock clock) {
		this.accounts = accounts;
		this.cookies = cookies;
		this.clock = clock;
	}

	@PutMapping("/password")
	@Operation(summary = "Change the password",
			description = "Logs out every other device. Returns a fresh access token and sets a new refresh "
					+ "cookie, so this client stays logged in.")
	@ApiResponse(responseCode = "200", description = "Password changed")
	@ApiResponse(responseCode = "400", description = "Current password incorrect, new password unchanged or "
			+ "too weak (code VALIDATION_FAILED, see errors[].field)")
	ResponseEntity<TokenResponse> changePassword(@CurrentUserId UUID userId,
			@Valid @RequestBody ChangePasswordRequest request,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
		AuthService.Tokens tokens = accounts.changePassword(userId, request.currentPassword(), request.newPassword(),
				userAgent);
		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, cookies.create(tokens.refreshToken()).toString())
			.body(TokenResponse.bearer(tokens.accessToken(), clock.instant()));
	}

	@DeleteMapping
	@Operation(summary = "Delete the account and all its data",
			description = "Permanent. Requires the password. Clears the refresh cookie.")
	@ApiResponse(responseCode = "204", description = "Account deleted")
	@ApiResponse(responseCode = "400", description = "Password incorrect (code VALIDATION_FAILED, field password)")
	ResponseEntity<Void> deleteAccount(@CurrentUserId UUID userId, @Valid @RequestBody DeleteAccountRequest request) {
		accounts.deleteAccount(userId, request.password());
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear().toString()).build();
	}

	record ChangePasswordRequest(@NotBlank @Size(max = 200) String currentPassword,
			@Schema(description = "At least 10 characters, at most 72 bytes") @NotBlank @ValidPassword
			String newPassword) {

		@Override
		public String toString() {
			return "ChangePasswordRequest[]";
		}

	}

	record DeleteAccountRequest(@Schema(description = "Current password, to confirm") @NotBlank @Size(max = 200)
	String password) {

		@Override
		public String toString() {
			return "DeleteAccountRequest[]";
		}

	}

}
