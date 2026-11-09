package com.linguaflow.auth;

import com.linguaflow.auth.password.ValidPassword;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegistrationRequest(
		@Schema(example = "anna@example.de") @NotBlank @Email @Size(max = 320) String email,
		@Schema(example = "correct-horse-battery", description = "At least 10 characters, at most 72 bytes")
		@NotBlank @ValidPassword String password,
		@Schema(example = "Anna") @NotBlank @Size(max = 100) String displayName) {

	@Override
	public String toString() {
		// Never let the password reach logs
		return "RegistrationRequest[email=" + email + ", displayName=" + displayName + "]";
	}

}
