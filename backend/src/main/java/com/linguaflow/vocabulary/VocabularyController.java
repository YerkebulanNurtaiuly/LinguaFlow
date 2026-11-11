package com.linguaflow.vocabulary;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.common.error.ApiProblem;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.common.security.CurrentUserId;
import com.linguaflow.common.web.PageResponse;
import com.linguaflow.vocabulary.VocabularyDtos.CreateRequest;
import com.linguaflow.vocabulary.VocabularyDtos.ItemResponse;
import com.linguaflow.vocabulary.VocabularyDtos.UpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.SortDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The learner's vocabulary (VOC-02). Words are listed and created in the active language. */
@RestController
@RequestMapping("/api/vocabulary")
@Tag(name = "Vocabulary", description = "The learner's words and phrases")
class VocabularyController {

	/** API sort names → entity attributes. "lemma" sorts by the normalized lemma, so articles don't group words. */
	private static final Map<String, String> SORTABLE = Map.of("createdAt", "createdAt", "updatedAt", "updatedAt",
			"lemma", "normalizedLemma", "translation", "translation", "cefrLevel", "cefrLevel", "status", "status");

	private final VocabularyService vocabulary;

	VocabularyController(VocabularyService vocabulary) {
		this.vocabulary = vocabulary;
	}

	@GetMapping
	@Operation(summary = "List vocabulary",
			description = "Words in the active language, newest first by default. Filters combine with AND; "
					+ "repeating a filter (e.g. status=NEW&status=LEARNING) means any of them. "
					+ "Sortable by: createdAt, updatedAt, lemma, translation, cefrLevel, status.")
	@ApiResponse(responseCode = "200", description = "One page of words")
	@ApiResponse(responseCode = "409", description = "No active language yet (code NO_ACTIVE_LANGUAGE)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	PageResponse<ItemResponse> list(@CurrentUserId UUID userId,
			@Parameter(description = "Text in the lemma (article, case and ß/ss ignored) or the translation")
			@RequestParam(name = "q", required = false) @Size(max = 200) String query,
			@RequestParam(required = false) List<PartOfSpeech> partOfSpeech,
			@RequestParam(required = false) List<CefrLevel> cefrLevel,
			@RequestParam(required = false) List<KnowledgeStatus> status,
			@Parameter(description = "Only words of this study session") @RequestParam(required = false)
			UUID sessionId,
			@Parameter(description = "Only words with this tag") @RequestParam(required = false) UUID tagId,
			@Parameter(description = "Only words in these AI enrichment states, e.g. PENDING_AI")
			@RequestParam(required = false) List<AiStatus> aiStatus,
			@SortDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		VocabularyFilter filter = new VocabularyFilter(query, toSet(partOfSpeech), toSet(cefrLevel), toSet(status),
				sessionId, tagId, toSet(aiStatus));
		var page = vocabulary.list(userId, filter, toEntitySort(pageable));
		// Report the page with the sort names the client used
		return PageResponse.from(new PageImpl<>(page.getContent(), pageable, page.getTotalElements()),
				ItemResponse::from);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get one word")
	ItemResponse get(@CurrentUserId UUID userId, @PathVariable UUID id) {
		return ItemResponse.from(vocabulary.get(userId, id));
	}

	@PostMapping
	@Operation(summary = "Add a word",
			description = "Added to the active language. A word you already have (same lemma ignoring article, "
					+ "case and ß/ss, and the same part of speech or none on either side) is rejected with 409 and "
					+ "its existingId.")
	@ApiResponse(responseCode = "201", description = "Word added")
	@ApiResponse(responseCode = "409",
			description = "Already in your vocabulary (code VOCABULARY_DUPLICATE, see existingId), or no active "
					+ "language yet (code NO_ACTIVE_LANGUAGE)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<ItemResponse> create(@CurrentUserId UUID userId, @Valid @RequestBody CreateRequest request) {
		VocabularyItem item = vocabulary.create(userId, request.toCommand());
		return ResponseEntity.created(URI.create("/api/vocabulary/" + item.getId())).body(ItemResponse.from(item));
	}

	@PatchMapping("/{id}")
	@Operation(summary = "Edit a word",
			description = "Partial update. Fields you edit stop counting as AI-generated.")
	@ApiResponse(responseCode = "200", description = "Word updated")
	@ApiResponse(responseCode = "409",
			description = "The new lemma clashes with another word (code VOCABULARY_DUPLICATE, see existingId), "
					+ "or the word was changed meanwhile (code CONFLICT)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ItemResponse update(@CurrentUserId UUID userId, @PathVariable UUID id,
			@Valid @RequestBody UpdateRequest request) {
		return ItemResponse.from(vocabulary.update(userId, id, request.toCommand()));
	}

	@DeleteMapping("/{id}")
	@Operation(summary = "Delete a word", description = "Also deletes its examples, flashcards and progress.")
	@ApiResponse(responseCode = "204", description = "Deleted")
	ResponseEntity<Void> delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
		vocabulary.delete(userId, id);
		return ResponseEntity.noContent().build();
	}

	private static <T> Set<T> toSet(List<T> values) {
		return values == null ? Set.of() : values.stream().collect(Collectors.toSet());
	}

	private static Pageable toEntitySort(Pageable pageable) {
		List<Sort.Order> orders = pageable.getSort().stream().map(order -> {
			String property = SORTABLE.get(order.getProperty());
			if (property == null) {
				throw new InvalidFieldException("sort", "can't sort by '" + order.getProperty() + "'; use one of "
						+ SORTABLE.keySet().stream().sorted().collect(Collectors.joining(", ")));
			}
			return order.withProperty(property);
		}).toList();
		return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(orders));
	}

}
