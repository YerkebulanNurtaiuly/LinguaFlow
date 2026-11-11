package com.linguaflow.user;

import java.util.List;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.domain.ExerciseType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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
			CefrLevel selfAssessedLevel, @Schema(description = "true = make this the active language") Boolean active,
			@Valid SettingsUpdate settings) {
	}

	/** Partial settings update (USR-03): absent fields stay unchanged. Ranges match the DB checks. */
	record SettingsUpdate(@Schema(example = "50") @Min(1) @Max(1000) Integer dailyReviewGoal,
			@Schema(example = "20") @Min(0) @Max(500) Integer newCardsPerDay,
			@Schema(description = "Empty list = no preference; duplicates are ignored") @Size(max = 20)
			List<@NotNull ExerciseType> preferredExerciseTypes,
			@Schema(example = "20") @Min(5) @Max(200) Integer practiceSessionLength,
			@Schema(description = "Flashcard directions to review; at least one") @Size(min = 1, max = 2)
			List<@NotNull CardDirection> cardDirections) {
	}

}
