package com.linguaflow.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(@Schema(example = "demo@linguaflow.local") @NotBlank @Size(max = 320) String email,
		@Schema(example = "demo-password-123") @NotBlank @Size(max = 200) String password) {

	@Override
	public String toString() {
		return "LoginRequest[email=" + email + "]";
	}

}
