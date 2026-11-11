package com.linguaflow.grammar;

import java.net.URI;
import java.time.Instant;
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
import com.linguaflow.grammar.GrammarTopicService.GrammarTopicChanges;
import com.linguaflow.grammar.GrammarTopicService.NewGrammarTopic;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

/** The learner's grammar topics (GRM-01), listed and created in the active language. */
@RestController
@RequestMapping("/api/grammar-topics")
@Tag(name = "Grammar", description = "The grammar topics you study and how well you know them")
class GrammarTopicController {

	/** API sort names → entity attributes; "title" sorts by the normalized title. */
	private static final Map<String, String> SORTABLE = Map.of("createdAt", "createdAt", "updatedAt", "updatedAt",
			"title", "normalizedTitle", "cefrLevel", "cefrLevel", "status", "status");

	private final GrammarTopicService topics;

	GrammarTopicController(GrammarTopicService topics) {
		this.topics = topics;
	}

	@Schema(name = "CreateGrammarTopicRequest", description = "Only the title is required")
	record CreateRequest(
			@Schema(example = "Dativ") @NotBlank @Size(max = GrammarTopicService.MAX_TITLE_LENGTH) String title,
			@Schema(example = "Indirect objects and dative prepositions")
			@Size(max = GrammarTopicService.MAX_DESCRIPTION_LENGTH) String description,
			@Schema(description = "Your own notes, in Markdown")
			@Size(max = GrammarTopicService.MAX_NOTES_LENGTH) String personalNotes, CefrLevel cefrLevel,
			@Schema(description = "Defaults to NEW; MASTERED is earned, not set") KnowledgeStatus status) {

		NewGrammarTopic toCommand() {
			return new NewGrammarTopic(title, description, personalNotes, cefrLevel, status);
		}

	}

	@Schema(name = "UpdateGrammarTopicRequest",
			description = "Partial update: absent or null fields stay unchanged; an empty string clears the "
					+ "description or notes")
	record UpdateRequest(
			@Size(min = 1, max = GrammarTopicService.MAX_TITLE_LENGTH)
			@Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") String title,
			@Size(max = GrammarTopicService.MAX_DESCRIPTION_LENGTH) String description,
			@Size(max = GrammarTopicService.MAX_NOTES_LENGTH) String personalNotes, CefrLevel cefrLevel,
			@Schema(description = "LEARNING or LEARNED; MASTERED only with confirmMastered (it is normally "
					+ "earned through practice); NEW can't be chosen again") KnowledgeStatus status,
			@Schema(description = "Set to true to mark the topic MASTERED by hand") Boolean confirmMastered) {

		GrammarTopicChanges toCommand() {
			return new GrammarTopicChanges(title, description, personalNotes, cefrLevel, status,
					Boolean.TRUE.equals(confirmMastered));
		}

	}

	@Schema(name = "GrammarExplanation",
			description = "AI-generated explanation (GRM-04), in your explanation language; kept apart from your notes")
	record ExplanationResponse(String summary,
			@Schema(description = "Most important first; a rule may contain a plain-text table, so keep its line breaks") List<String> rules,
			List<ExampleResponse> examples, List<String> commonMistakes) {

		static ExplanationResponse from(GrammarExplanation explanation) {
			return explanation == null ? null
					: new ExplanationResponse(explanation.summary(), explanation.rules(),
							explanation.examples()
								.stream()
								.map(example -> new ExampleResponse(example.sentence(), example.translation()))
								.toList(),
							explanation.commonMistakes());
		}

	}

	@Schema(name = "GrammarExplanationExample")
	record ExampleResponse(@Schema(description = "In the learned language") String sentence, String translation) {
	}

	@Schema(name = "GrammarTopicStatusChange")
	record StatusChangeResponse(@Schema(description = "Null for the first entry") KnowledgeStatus fromStatus,
			KnowledgeStatus toStatus,
			@Schema(description = "CREATED, MANUAL (by you), MASTERY_RULE or DEMOTION_RULE (by your progress)")
			StatusChangeReason reason, Instant changedAt) {

		static StatusChangeResponse from(GrammarTopicStatusChange change) {
			return new StatusChangeResponse(change.getFromStatus(), change.getToStatus(), change.getReason(),
					change.getChangedAt());
		}

	}

	@Schema(name = "GrammarTopic")
	record TopicResponse(UUID id, @Schema(example = "Dativ") String title, String description,
			@Schema(description = "Markdown") String personalNotes, CefrLevel cefrLevel, KnowledgeStatus status,
			@Schema(description = "Catalog entry it was picked from (GRM-03)") UUID catalogTopicId,
			ExplanationResponse aiExplanation, Instant aiExplanationGeneratedAt, Instant createdAt,
			Instant updatedAt, @Schema(description = "Increases with every change") long version) {

		static TopicResponse from(GrammarTopic topic) {
			return new TopicResponse(topic.getId(), topic.getTitle(), topic.getDescription(),
					topic.getPersonalNotes(), topic.getCefrLevel(), topic.getStatus(), topic.getCatalogTopicId(),
					ExplanationResponse.from(topic.getAiExplanation()), topic.getAiExplanationGeneratedAt(),
					topic.getCreatedAt(), topic.getUpdatedAt(), topic.getVersion());
		}

	}

	@GetMapping
	@Operation(summary = "List grammar topics",
			description = "Topics of the active language, newest first by default. Repeating a filter means any "
					+ "of them. Sortable by: createdAt, updatedAt, title, cefrLevel, status.")
	@ApiResponse(responseCode = "200", description = "One page of topics")
	@ApiResponse(responseCode = "409", description = "No active language yet (code NO_ACTIVE_LANGUAGE)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	PageResponse<TopicResponse> list(@CurrentUserId UUID userId,
			@Parameter(description = "Text in the title or description") @RequestParam(name = "q",
					required = false) @Size(max = 200) String query,
			@RequestParam(required = false) List<CefrLevel> cefrLevel,
			@RequestParam(required = false) List<KnowledgeStatus> status,
			@Parameter(description = "Only topics of this study session") @RequestParam(required = false)
			UUID sessionId,
			@SortDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		var page = topics.list(userId, new GrammarTopicFilter(query, toSet(cefrLevel), toSet(status), sessionId),
				toEntitySort(pageable));
		return PageResponse.from(new PageImpl<>(page.getContent(), pageable, page.getTotalElements()),
				TopicResponse::from);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get one grammar topic")
	TopicResponse get(@CurrentUserId UUID userId, @PathVariable UUID id) {
		return TopicResponse.from(topics.get(userId, id));
	}

	@PostMapping
	@Operation(summary = "Add a grammar topic",
			description = "Added to the active language. A title you already have (ignoring case, spacing, a "
					+ "leading article and ß/ss) is rejected with 409 and its existingId.")
	@ApiResponse(responseCode = "201", description = "Topic added")
	@ApiResponse(responseCode = "409",
			description = "Already one of your topics (code GRAMMAR_TOPIC_DUPLICATE, see existingId), or no "
					+ "active language yet (code NO_ACTIVE_LANGUAGE)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<TopicResponse> create(@CurrentUserId UUID userId, @Valid @RequestBody CreateRequest request) {
		GrammarTopic topic = topics.create(userId, request.toCommand());
		return ResponseEntity.created(URI.create("/api/grammar-topics/" + topic.getId()))
			.body(TopicResponse.from(topic));
	}

	@Schema(name = "GrammarExplanationRequest", description = "The AI job writing the explanation")
	record ExplanationRequestResponse(UUID aiJobId,
			@Schema(description = "True when an earlier request is still being worked on; this is its job") boolean alreadyRunning) {
	}

	@PostMapping("/{id}/explanation")
	@Operation(summary = "Ask the AI to explain the topic",
			description = "Queues an AI explanation (summary, rules, examples, common mistakes) in your explanation "
					+ "language, replacing an earlier one; your notes are never changed. Poll GET /api/ai/jobs/{aiJobId} "
					+ "until it's finished, then reload the topic. While one is still being written, its job is "
					+ "returned instead of queueing another.")
	@ApiResponse(responseCode = "202", description = "The explanation is being written")
	ResponseEntity<ExplanationRequestResponse> requestExplanation(@CurrentUserId UUID userId, @PathVariable UUID id) {
		GrammarTopicService.ExplanationRequest request = topics.requestExplanation(userId, id);
		return ResponseEntity.accepted()
			.body(new ExplanationRequestResponse(request.aiJobId(), request.alreadyRunning()));
	}

	@GetMapping("/{id}/status-history")
	@Operation(summary = "Status history of a grammar topic", description = "Every status change, oldest first.")
	List<StatusChangeResponse> history(@CurrentUserId UUID userId, @PathVariable UUID id) {
		return topics.history(userId, id).stream().map(StatusChangeResponse::from).toList();
	}

	@PatchMapping("/{id}")
	@Operation(summary = "Edit a grammar topic",
			description = "Partial update. Status changes are recorded in the status history.")
	@ApiResponse(responseCode = "200", description = "Topic updated")
	@ApiResponse(responseCode = "409",
			description = "The new title clashes with another topic (code GRAMMAR_TOPIC_DUPLICATE, see "
					+ "existingId), or the topic was changed meanwhile (code CONFLICT)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	TopicResponse update(@CurrentUserId UUID userId, @PathVariable UUID id,
			@Valid @RequestBody UpdateRequest request) {
		return TopicResponse.from(topics.update(userId, id, request.toCommand()));
	}

	@DeleteMapping("/{id}")
	@Operation(summary = "Delete a grammar topic", description = "Also deletes its status history and progress.")
	@ApiResponse(responseCode = "204", description = "Deleted")
	ResponseEntity<Void> delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
		topics.delete(userId, id);
		return ResponseEntity.noContent().build();
	}

	private static <T> Set<T> toSet(List<T> values) {
		return values == null ? Set.of() : Set.copyOf(values);
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
