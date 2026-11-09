package com.linguaflow.auth;

import java.util.UUID;

import com.linguaflow.auth.AccountRequests.PasswordResetRequest;
import com.linguaflow.auth.AccountRequests.ResetPasswordRequest;
import com.linguaflow.auth.AccountRequests.VerifyEmailRequest;
import com.linguaflow.common.error.ApiProblem;
import com.linguaflow.common.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Registration, login and session tokens")
class AccountController {

	private final AccountService accounts;

	AccountController(AccountService accounts) {
		this.accounts = accounts;
	}

	@PostMapping("/verify-email/request")
	@Operation(summary = "Send a new email verification link to the current user")
	@ApiResponse(responseCode = "202", description = "Sent (or nothing to do: already verified)")
	ResponseEntity<Void> requestVerification(@CurrentUserId UUID userId) {
		accounts.sendVerificationEmail(userId);
		return ResponseEntity.accepted().build();
	}

	@PostMapping("/verify-email")
	@SecurityRequirements
	@Operation(summary = "Confirm the email address with the token from the verification link")
	@ApiResponse(responseCode = "204", description = "Email verified")
	@ApiResponse(responseCode = "400", description = "Invalid, expired or used link (code INVALID_TOKEN)")
	ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
		accounts.verifyEmail(request.token());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/password-reset/request")
	@SecurityRequirements
	@Operation(summary = "Email a password reset link",
			description = "Always 202, whether or not the email is registered, so accounts cannot be discovered.")
	@ApiResponse(responseCode = "202", description = "If the account exists, a link was sent")
	@ApiResponse(responseCode = "429", description = "Too many reset requests from this IP (code TOO_MANY_REQUESTS)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
		accounts.requestPasswordReset(request.email());
		return ResponseEntity.accepted().build();
	}

	@PostMapping("/password-reset")
	@SecurityRequirements
	@Operation(summary = "Set a new password with the token from the reset link",
			description = "Logs the user out on all devices.")
	@ApiResponse(responseCode = "204", description = "Password changed")
	@ApiResponse(responseCode = "400", description = "Invalid, expired or used link (code INVALID_TOKEN), or the "
			+ "password does not meet the policy (code VALIDATION_FAILED)")
	ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
		accounts.resetPassword(request.token(), request.newPassword());
		return ResponseEntity.noContent().build();
	}

}
