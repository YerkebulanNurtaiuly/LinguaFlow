package com.linguaflow.user;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.linguaflow.common.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

	private final DataExportService exports;

	private final Clock clock;

	UserProfileController(UserProfileService profiles, DataExportService exports, Clock clock) {
		this.profiles = profiles;
		this.exports = exports;
		this.clock = clock;
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

	@GetMapping("/export")
	@Operation(summary = "Download all your data (SEC-02)",
			description = "Everything stored about you, as one JSON file: your account, learning languages and "
					+ "settings, words, tags, grammar topics, lessons, flashcards and reviews, exercises and "
					+ "answers, practice sessions, progress, and your AI jobs and AI usage. Password and token "
					+ "hashes are never included. Sent as an attachment.")
	@ApiResponse(responseCode = "200", description = "The export",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = DataExport.class)))
	ResponseEntity<Map<String, Object>> export(@CurrentUserId UUID userId) {
		String filename = "linguaflow-export-" + LocalDate.now(clock) + ".json";
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(filename).build().toString())
			.body(exports.export(userId));
	}

	/** Documents the export's shape for the API docs; the body itself is built as a map. */
	@Schema(name = "DataExport", description = "A user's complete data export")
	record DataExport(@Schema(example = "linguaflow-export") String format,
			@Schema(description = "Bumped when the shape changes") int version, String exportedAt,
			@Schema(description = "The account, without the password hash") Map<String, Object> account,
			@Schema(description = "Table name to the user's rows; each row maps column names to values") Map<String, List<Map<String, Object>>> tables,
			@Schema(description = "Tables deliberately left out: secrets and shared data") List<String> notExported) {
	}

}
