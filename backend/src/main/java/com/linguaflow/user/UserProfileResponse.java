package com.linguaflow.user;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record UserProfileResponse(UUID id, String email, String displayName, Role role, boolean emailVerified,
		@Schema(example = "en") String interfaceLanguage, @Schema(example = "Europe/Berlin") String timezone,
		Instant createdAt) {

	static UserProfileResponse from(User user) {
		return new UserProfileResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
				user.getEmailVerifiedAt() != null, user.getInterfaceLanguageCode(), user.getTimezone(),
				user.getCreatedAt());
	}

}
