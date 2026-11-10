package com.linguaflow.grammar;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.common.error.ApiProblem;
import com.linguaflow.common.security.CurrentUserId;
import com.linguaflow.grammar.GrammarCatalogService.CatalogItem;
import com.linguaflow.grammar.GrammarTopicController.TopicResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Suggested grammar topics to pick from instead of typing (GRM-03). */
@RestController
@RequestMapping("/api/grammar-topics/catalog")
@Tag(name = "Grammar", description = "The grammar topics you study and how well you know them")
class GrammarCatalogController {

	private final GrammarCatalogService catalog;

	GrammarCatalogController(GrammarCatalogService catalog) {
		this.catalog = catalog;
	}

	@Schema(name = "GrammarCatalogEntry")
	record EntryResponse(UUID id, @Schema(example = "dativ") String slug, @Schema(example = "Dativ") String title,
			String description, CefrLevel cefrLevel,
			@Schema(description = "Your topic for this entry, if you already have it (picked from here or with the "
					+ "same title); null if you can add it") UUID topicId) {

		static EntryResponse from(CatalogItem item) {
			GrammarTopicCatalogEntry entry = item.entry();
			return new EntryResponse(entry.getId(), entry.getSlug(), entry.getTitle(), entry.getDescription(),
					entry.getCefrLevel(), item.topicId());
		}

	}

	@Schema(name = "AddCatalogTopicRequest")
	record AddRequest(@Schema(description = "Defaults to NEW; MASTERED is earned, not set") KnowledgeStatus status) {
	}

	@GetMapping
	@Operation(summary = "List suggested grammar topics",
			description = "The catalog of the active language, by level then title, with the topics you already "
					+ "have marked. Repeat cefrLevel for several levels.")
	@ApiResponse(responseCode = "200", description = "The catalog entries")
	@ApiResponse(responseCode = "409", description = "No active language yet (code NO_ACTIVE_LANGUAGE)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	List<EntryResponse> list(@CurrentUserId UUID userId, @RequestParam(required = false) List<CefrLevel> cefrLevel) {
		return catalog.list(userId, cefrLevel == null ? Set.of() : Set.copyOf(cefrLevel))
			.stream()
			.map(EntryResponse::from)
			.toList();
	}

	@PostMapping("/{id}")
	@Operation(summary = "Add a suggested topic",
			description = "Adds the entry as one of your topics, with its title, description and level, in the "
					+ "active language. The body is optional.")
	@ApiResponse(responseCode = "201", description = "Topic added")
	@ApiResponse(responseCode = "409",
			description = "You already have a topic with that title (code GRAMMAR_TOPIC_DUPLICATE, see existingId)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<TopicResponse> add(@CurrentUserId UUID userId, @PathVariable UUID id,
			@RequestBody(required = false) AddRequest request) {
		GrammarTopic topic = catalog.add(userId, id, request == null ? null : request.status());
		return ResponseEntity.created(URI.create("/api/grammar-topics/" + topic.getId()))
			.body(TopicResponse.from(topic));
	}

}
