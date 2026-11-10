package com.linguaflow.vocabulary;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.error.ApiProblem;
import com.linguaflow.common.security.CurrentUserId;
import com.linguaflow.vocabulary.TagService.TagWithCount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The learner's tags for grouping words (VOC-03). Words get tags through {@code tagIds}
 * on the vocabulary create and edit requests, and are filtered with
 * {@code GET /api/vocabulary?tagId=}.
 */
@RestController
@RequestMapping("/api/tags")
@Tag(name = "Tags", description = "Your labels for grouping words, per learning language")
class TagController {

	private final TagService tags;

	TagController(TagService tags) {
		this.tags = tags;
	}

	@Schema(name = "VocabularyTagSummary")
	record TagSummary(UUID id, @Schema(example = "Food") String name,
			@Schema(description = "How many of your words carry this tag") long wordCount, Instant createdAt,
			Instant updatedAt) {

		static TagSummary from(TagWithCount entry) {
			com.linguaflow.vocabulary.Tag tag = entry.tag();
			return new TagSummary(tag.getId(), tag.getName(), entry.wordCount(), tag.getCreatedAt(),
					tag.getUpdatedAt());
		}

	}

	@Schema(name = "TagNameRequest")
	record NameRequest(@Schema(example = "Chapter 3",
			description = "1–50 characters; must be unique ignoring case") @NotBlank @Size(max = TagService.MAX_NAME_LENGTH) String name) {
	}

	@GetMapping
	@Operation(summary = "List tags", description = "All tags of the active language, by name, with word counts.")
	@ApiResponse(responseCode = "200", description = "The tags")
	@ApiResponse(responseCode = "409", description = "No active language yet (code NO_ACTIVE_LANGUAGE)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	List<TagSummary> list(@CurrentUserId UUID userId) {
		return tags.list(userId).stream().map(TagSummary::from).toList();
	}

	@PostMapping
	@Operation(summary = "Create a tag", description = "Created in the active language.")
	@ApiResponse(responseCode = "201", description = "Tag created")
	@ApiResponse(responseCode = "409",
			description = "A tag with that name exists (code TAG_NAME_TAKEN, see existingId), or no active "
					+ "language yet (code NO_ACTIVE_LANGUAGE)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<TagSummary> create(@CurrentUserId UUID userId, @Valid @RequestBody NameRequest request) {
		TagSummary created = TagSummary.from(tags.create(userId, request.name()));
		return ResponseEntity.created(URI.create("/api/tags/" + created.id())).body(created);
	}

	@PatchMapping("/{id}")
	@Operation(summary = "Rename a tag")
	@ApiResponse(responseCode = "200", description = "Tag renamed")
	@ApiResponse(responseCode = "409", description = "Another tag has that name (code TAG_NAME_TAKEN, see existingId)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	TagSummary rename(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody NameRequest request) {
		return TagSummary.from(tags.rename(userId, id, request.name()));
	}

	@DeleteMapping("/{id}")
	@Operation(summary = "Delete a tag", description = "The words stay; they just lose this tag.")
	@ApiResponse(responseCode = "204", description = "Deleted")
	ResponseEntity<Void> delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
		tags.delete(userId, id);
		return ResponseEntity.noContent().build();
	}

}
