package com.linguaflow.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Partial update: fields that are absent (null) stay unchanged. */
public record UpdateProfileRequest(
		@Schema(example = "Anna") @Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank")
		String displayName,
		@Schema(example = "en", description = "ISO 639-1 code of a supported language") @Pattern(regexp = "[a-z]{2,3}")
		String interfaceLanguage,
		@Schema(example = "Europe/Berlin", description = "IANA time zone; defines the user's today for reviews and streaks")
		@Size(max = 64) String timezone) {
}
