package com.linguaflow.user;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.security.CurrentUserId;
import com.linguaflow.user.UserLanguageRequests.AddLanguageRequest;
import com.linguaflow.user.UserLanguageRequests.UpdateLanguageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Languages", description = "Available languages and the user's learning languages")
class LanguageController {

	private final LanguageRepository languages;

	private final UserLanguageService userLanguages;

	LanguageController(LanguageRepository languages, UserLanguageService userLanguages) {
		this.languages = languages;
		this.userLanguages = userLanguages;
	}

	@GetMapping("/api/languages")
	@Operation(summary = "List all languages and what they can be used for")
	List<LanguageResponse> listLanguages() {
		return languages.findAll(Sort.by("code")).stream().map(LanguageResponse::from).toList();
	}

	@GetMapping("/api/users/me/languages")
	@Operation(summary = "List the languages the current user learns")
	List<UserLanguageResponse> listMine(@CurrentUserId UUID userId) {
		return userLanguages.list(userId).stream().map(UserLanguageResponse::from).toList();
	}

	@PostMapping("/api/users/me/languages")
	@Operation(summary = "Start learning a language",
			description = "The first language becomes active automatically; pass active=true to switch to it.")
	@ApiResponse(responseCode = "201", description = "Language added")
	@ApiResponse(responseCode = "409", description = "Already learning this language (code CONFLICT)")
	ResponseEntity<UserLanguageResponse> add(@CurrentUserId UUID userId,
			@Valid @RequestBody AddLanguageRequest request) {
		UserLanguage added = userLanguages.add(userId, request);
		return ResponseEntity.created(URI.create("/api/users/me/languages/" + added.getLanguageCode()))
			.body(UserLanguageResponse.from(added));
	}

	@PatchMapping("/api/users/me/languages/{languageCode}")
	@Operation(summary = "Change explanation language or level, or make this the active language")
	UserLanguageResponse update(@CurrentUserId UUID userId, @PathVariable String languageCode,
			@Valid @RequestBody UpdateLanguageRequest request) {
		return UserLanguageResponse.from(userLanguages.update(userId, languageCode, request));
	}

	@DeleteMapping("/api/users/me/languages/{languageCode}")
	@Operation(summary = "Stop learning a language",
			description = "Permanently deletes ALL learning data for this language (vocabulary, grammar, "
					+ "sessions, reviews, progress). Clients must ask for confirmation first.")
	@ApiResponse(responseCode = "204", description = "Removed")
	ResponseEntity<Void> remove(@CurrentUserId UUID userId, @PathVariable String languageCode) {
		userLanguages.remove(userId, languageCode);
		return ResponseEntity.noContent().build();
	}

}
