package com.linguaflow.user;

import com.linguaflow.common.domain.CefrLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

final class UserLanguageRequests {

	private UserLanguageRequests() {
	}

	record AddLanguageRequest(
			@Schema(example = "de", description = "Target language to learn") @NotBlank @Pattern(regexp = "[a-z]{2,3}")
			String languageCode,
			@Schema(example = "en", description = "Language for translations and explanations") @NotBlank
			@Pattern(regexp = "[a-z]{2,3}") String explanationLanguageCode,
			CefrLevel selfAssessedLevel,
			@Schema(description = "Make it the active language (the first language always becomes active)")
			Boolean active) {
	}

	/** Partial update; {@code active} can only be set to true (activate this one instead). */
	record UpdateLanguageRequest(@Schema(example = "en") @Pattern(regexp = "[a-z]{2,3}") String explanationLanguageCode,
			CefrLevel selfAssessedLevel, @Schema(description = "true = make this the active language") Boolean active) {
	}

}
