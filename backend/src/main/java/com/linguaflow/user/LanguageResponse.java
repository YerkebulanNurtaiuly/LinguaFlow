package com.linguaflow.user;

import io.swagger.v3.oas.annotations.media.Schema;

public record LanguageResponse(@Schema(example = "de") String code, @Schema(example = "German") String name,
		@Schema(example = "Deutsch") String nativeName,
		@Schema(description = "Can be learned (target language)") boolean supportedAsTarget,
		@Schema(description = "Can be used for translations and explanations") boolean supportedAsExplanation) {

	static LanguageResponse from(Language language) {
		return new LanguageResponse(language.getCode(), language.getName(), language.getNativeName(),
				language.isSupportedAsTarget(), language.isSupportedAsExplanation());
	}

}
