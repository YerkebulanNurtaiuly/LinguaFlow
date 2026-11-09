package com.linguaflow.auth;

import java.time.Instant;
import java.util.UUID;

import com.linguaflow.user.User;

public record RegistrationResponse(UUID id, String email, String displayName, Instant createdAt) {

	static RegistrationResponse from(User user) {
		return new RegistrationResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
	}

}
