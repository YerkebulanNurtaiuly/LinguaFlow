package com.linguaflow.user;

import java.time.Instant;

import com.linguaflow.common.domain.CefrLevel;
import io.swagger.v3.oas.annotations.media.Schema;

public record UserLanguageResponse(@Schema(example = "de") String languageCode,
		@Schema(example = "en") String explanationLanguageCode, CefrLevel selfAssessedLevel, boolean active,
		Instant createdAt) {

	static UserLanguageResponse from(UserLanguage language) {
		return new UserLanguageResponse(language.getLanguageCode(), language.getExplanationLanguageCode(),
				language.getSelfAssessedLevel(), language.isActive(), language.getCreatedAt());
	}

}
