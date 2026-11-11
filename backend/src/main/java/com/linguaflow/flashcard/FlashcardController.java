package com.linguaflow.flashcard;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.common.security.CurrentUserId;
import com.linguaflow.common.web.PageResponse;
import com.linguaflow.flashcard.FlashcardService.CardFilter;
import com.linguaflow.flashcard.FlashcardService.CardView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.data.web.SortDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The learner's flashcards (FC-02): browse them, and leave some out of reviews. */
@RestController
@RequestMapping("/api/flashcards")
@Tag(name = "Flashcards", description = "Cards made from your words, two per word")
public class FlashcardController {

	/** API sort names → JPQL; the due date lives on the review state. */
	private static final Map<String, String> SORTABLE = Map.of("createdAt", "f.createdAt", "updatedAt",
			"f.updatedAt", "dueAt", "s.dueAt");

	private final FlashcardService flashcards;

	FlashcardController(FlashcardService flashcards) {
		this.flashcards = flashcards;
	}

	@Schema(name = "Flashcard", description = "One direction of one word, with what it shows and where it stands in review")
	record CardResponse(UUID id, UUID vocabularyItemId, @Schema(description = "The word as you entered it") String lemma,
			CardDirection direction,
			@Schema(description = "Left out of reviews; cards of the directions you don't review are skipped too") boolean suspended,
			ContentResponse content, SrsResponse srs, Instant createdAt, Instant updatedAt) {

		static CardResponse from(CardView view) {
			Flashcard card = view.card();
			return new CardResponse(card.getId(), card.getVocabularyItemId(), view.lemma(), card.getDirection(),
					card.isSuspended(), ContentResponse.from(view.content()), SrsResponse.from(view.srs()),
					card.getCreatedAt(), card.getUpdatedAt());
		}

	}

	@Schema(name = "FlashcardContent",
			description = "Built from the word every time, so it always matches the word as it is now")
	record ContentResponse(@Schema(description = "What's asked, e.g. \"der Hund\" or \"dog\"") String front,
			@Schema(description = "Shown with the front, e.g. \"noun\"") String hint,
			@Schema(description = "The answer") String back, @Schema(example = "die Hunde") String plural,
			@Schema(description = "Präteritum, e.g. \"lief\"") String praeteritum,
			@Schema(description = "Perfect with its auxiliary, e.g. \"ist gelaufen\"") String perfect,
			List<ExampleResponse> examples,
			@Schema(description = "False while the word has no translation; such cards aren't reviewed") boolean complete) {

		static ContentResponse from(FlashcardContent content) {
			return new ContentResponse(content.front(), content.hint(), content.back(), content.plural(),
					content.verbForms() == null ? null : content.verbForms().praeteritum(),
					content.verbForms() == null ? null : content.verbForms().perfect(),
					content.examples()
						.stream()
						.map(example -> new ExampleResponse(example.sentence(), example.translation()))
						.toList(),
					content.complete());
		}

	}

	@Schema(name = "FlashcardExample")
	record ExampleResponse(String sentence, String translation) {
	}

	@Schema(name = "FlashcardSrs", description = "Spaced-repetition state")
	record SrsResponse(SrsState.State state, @Schema(description = "Null while NEW") Instant dueAt, int intervalDays,
			int repetitions, int lapses, Instant lastReviewedAt) {

		static SrsResponse from(SrsState srs) {
			return new SrsResponse(srs.getState(), srs.getDueAt(), srs.getIntervalDays(), srs.getRepetitions(),
					srs.getLapses(), srs.getLastReviewedAt());
		}

	}

	@Schema(name = "UpdateFlashcardRequest")
	record UpdateRequest(@Schema(description = "True leaves the card out of reviews") @NotNull Boolean suspended) {
	}

	@GetMapping
	@Operation(summary = "List flashcards",
			description = "Cards of the active language's words, newest first. Sortable by createdAt, updatedAt or "
					+ "dueAt (NEW cards, which have no due date, come last).")
	@ApiResponse(responseCode = "200", description = "One page of cards")
	PageResponse<CardResponse> list(@CurrentUserId UUID userId,
			@Parameter(description = "Only these directions (repeatable)") @RequestParam(
					required = false) List<CardDirection> direction,
			@Parameter(description = "Only these review states (repeatable)") @RequestParam(
					required = false) List<SrsState.State> state,
			@Parameter(description = "Only suspended (true) or active (false) cards") @RequestParam(
					required = false) @Nullable Boolean suspended,
			@Parameter(description = "Only the cards of this word") @RequestParam(
					required = false) @Nullable UUID vocabularyItemId,
			@Parameter(description = "Searches the word and its translation") @RequestParam(
					required = false) @Nullable String q,
			@SortDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		var page = flashcards.list(userId, new CardFilter(direction, state, suspended, vocabularyItemId, q),
				toQuerySort(pageable));
		return PageResponse.from(new PageImpl<>(page.getContent(), pageable, page.getTotalElements()),
				CardResponse::from);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get a flashcard")
	@ApiResponse(responseCode = "200", description = "The card")
	CardResponse get(@CurrentUserId UUID userId, @PathVariable UUID id) {
		return CardResponse.from(flashcards.get(userId, id));
	}

	@PatchMapping("/{id}")
	@Operation(summary = "Suspend or unsuspend a flashcard",
			description = "A suspended card is left out of reviews; its review history is kept.")
	@ApiResponse(responseCode = "200", description = "The card")
	CardResponse update(@CurrentUserId UUID userId, @PathVariable UUID id,
			@Valid @RequestBody UpdateRequest request) {
		return CardResponse.from(flashcards.setSuspended(userId, id, request.suspended()));
	}

	/** Maps API sort names to the query's aliases; anything else is a 400 on sort. */
	private static Pageable toQuerySort(Pageable pageable) {
		Sort sort = Sort.unsorted();
		for (Sort.Order order : pageable.getSort()) {
			String property = SORTABLE.get(order.getProperty());
			if (property == null) {
				throw new InvalidFieldException("sort", "can't sort by '" + order.getProperty() + "'; use one of "
						+ SORTABLE.keySet().stream().sorted().collect(Collectors.joining(", ")));
			}
			sort = sort.and(JpaSort.unsafe(order.getDirection(), property));
		}
		return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort.and(JpaSort.unsafe("f.id")));
	}

}
