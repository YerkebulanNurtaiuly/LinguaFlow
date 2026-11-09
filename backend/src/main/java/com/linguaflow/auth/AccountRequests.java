package com.linguaflow.auth;

import com.linguaflow.auth.password.ValidPassword;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request bodies of the account endpoints (AUTH-07). */
final class AccountRequests {

	private AccountRequests() {
	}

	record VerifyEmailRequest(@Schema(description = "Token from the verification link") @NotBlank @Size(max = 200)
	String token) {
	}

	record PasswordResetRequest(@Schema(example = "anna@example.de") @NotBlank @Email @Size(max = 320) String email) {
	}

	record ResetPasswordRequest(@Schema(description = "Token from the reset link") @NotBlank @Size(max = 200) String token,
			@Schema(description = "At least 10 characters, at most 72 bytes") @NotBlank @ValidPassword String newPassword) {

		@Override
		public String toString() {
			return "ResetPasswordRequest[]";
		}

	}

}
