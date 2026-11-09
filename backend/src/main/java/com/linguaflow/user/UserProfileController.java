package com.linguaflow.user;

import java.util.UUID;

import com.linguaflow.common.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me")
@Tag(name = "Profile", description = "The current user's account (password change and deletion: see Auth)")
class UserProfileController {

	private final UserProfileService profiles;

	UserProfileController(UserProfileService profiles) {
		this.profiles = profiles;
	}

	@GetMapping
	@Operation(summary = "Get the current user's profile")
	UserProfileResponse get(@CurrentUserId UUID userId) {
		return UserProfileResponse.from(profiles.get(userId));
	}

	@PatchMapping
	@Operation(summary = "Update display name, interface language and/or time zone",
			description = "Only the fields present in the body are changed.")
	UserProfileResponse update(@CurrentUserId UUID userId, @Valid @RequestBody UpdateProfileRequest request) {
		return UserProfileResponse.from(profiles.update(userId, request));
	}

}
