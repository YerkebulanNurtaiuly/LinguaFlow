package com.linguaflow.vocabulary;

import java.util.List;
import java.util.UUID;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.error.ApiProblem;
import com.linguaflow.common.security.CurrentUserId;
import com.linguaflow.vocabulary.BulkVocabularyParser.Format;
import com.linguaflow.vocabulary.BulkVocabularyService.BulkItem;
import com.linguaflow.vocabulary.BulkVocabularyService.ItemResult;
import com.linguaflow.vocabulary.BulkVocabularyService.ItemStatus;
import com.linguaflow.vocabulary.BulkVocabularyService.LineStatus;
import com.linguaflow.vocabulary.BulkVocabularyService.PreviewLine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bulk vocabulary input (VOC-04): preview pasted text, then confirm the lines to keep.
 * The preview saves nothing; the client sends the chosen items back to confirm.
 */
@RestController
@RequestMapping("/api/vocabulary/bulk")
@Tag(name = "Vocabulary", description = "The learner's words and phrases")
class BulkVocabularyController {

	/** Room for 500 lines with translations and notes. */
	static final int MAX_TEXT_LENGTH = 200_000;

	private final BulkVocabularyService bulk;

	BulkVocabularyController(BulkVocabularyService bulk) {
		this.bulk = bulk;
	}

	@Schema(name = "BulkVocabularyPreviewRequest")
	record PreviewRequest(
			@Schema(example = "der Hund - dog\ndie Katze - cat\nlaufen",
					description = "TEXT: one word per line, optionally 'word - translation' (also '=', tab, en or em "
							+ "dash). CSV: comma or semicolon separated; columns lemma, translation, or any order with "
							+ "a header row naming lemma|word, translation, partOfSpeech|pos, cefrLevel|level, notes. "
							+ "At most 500 lines.")
			@NotBlank @Size(max = MAX_TEXT_LENGTH) String text,
			@Schema(description = "Defaults to TEXT") Format format) {
	}

	@Schema(name = "BulkVocabularyPreviewLine")
	record PreviewLineResponse(@Schema(description = "1-based line in the input; blank lines are counted") int line,
			@Schema(description = "The line as written") String text, LineStatus status, String lemma,
			String translation, PartOfSpeech partOfSpeech, CefrLevel cefrLevel, String notes,
			@Schema(description = "DUPLICATE of a word you have: its ID") UUID existingId,
			@Schema(description = "DUPLICATE of an earlier line of this input: its line") Integer duplicateOfLine,
			@Schema(description = "Why the line is DUPLICATE or INVALID", example = "already in your vocabulary")
			String message) {

		static PreviewLineResponse from(PreviewLine line) {
			var p = line.parsed();
			return new PreviewLineResponse(p.lineNumber(), p.text(), line.status(), p.lemma(), p.translation(),
					p.partOfSpeech(), p.cefrLevel(), p.notes(), line.existingId(), line.duplicateOfLine(),
					line.message());
		}

	}

	@Schema(name = "BulkVocabularyPreview")
	record PreviewResponse(List<PreviewLineResponse> lines, int newCount, int duplicateCount, int invalidCount) {

		static PreviewResponse from(List<PreviewLine> lines) {
			return new PreviewResponse(lines.stream().map(PreviewLineResponse::from).toList(),
					count(lines, LineStatus.NEW), count(lines, LineStatus.DUPLICATE), count(lines, LineStatus.INVALID));
		}

		private static int count(List<PreviewLine> lines, LineStatus status) {
			return (int) lines.stream().filter(l -> l.status() == status).count();
		}

	}

	@Schema(name = "BulkVocabularyItem", description = "A previewed line the learner kept, possibly edited")
	record ItemRequest(@Schema(example = "der Hund") @NotBlank @Size(max = VocabularyService.MAX_LEMMA_LENGTH) String lemma,
			@Schema(example = "dog") @Size(max = BulkVocabularyParser.MAX_TRANSLATION_LENGTH) String translation,
			PartOfSpeech partOfSpeech, CefrLevel cefrLevel,
			@Size(max = BulkVocabularyParser.MAX_NOTES_LENGTH) String notes) {

		BulkItem toItem() {
			return new BulkItem(lemma, translation, partOfSpeech, cefrLevel, notes);
		}

	}

	@Schema(name = "BulkVocabularyConfirmRequest")
	record ConfirmRequest(
			@NotEmpty @Size(max = BulkVocabularyService.MAX_ITEMS) List<@Valid @NotNull ItemRequest> items,
			@Schema(description = "Tags to put on every added word")
			@Size(max = TagService.MAX_TAGS_PER_WORD) List<@NotNull UUID> tagIds) {
	}

	@Schema(name = "BulkVocabularyItemResult")
	record ItemResultResponse(@Schema(description = "0-based position in the request's items") int index,
			String lemma, ItemStatus status, @Schema(description = "CREATED: the new word's ID") UUID id,
			@Schema(description = "DUPLICATE: the word you already have") UUID existingId,
			@Schema(description = "INVALID: the field at fault") String field, String message) {

		static ItemResultResponse from(ItemResult result) {
			return new ItemResultResponse(result.index(), result.lemma(), result.status(), result.id(),
					result.existingId(), result.field(), result.message());
		}

	}

	@Schema(name = "BulkVocabularyResult")
	record ConfirmResponse(List<ItemResultResponse> results, int createdCount, int duplicateCount,
			int invalidCount) {

		static ConfirmResponse from(List<ItemResult> results) {
			return new ConfirmResponse(results.stream().map(ItemResultResponse::from).toList(),
					count(results, ItemStatus.CREATED), count(results, ItemStatus.DUPLICATE),
					count(results, ItemStatus.INVALID));
		}

		private static int count(List<ItemResult> results, ItemStatus status) {
			return (int) results.stream().filter(r -> r.status() == status).count();
		}

	}

	@PostMapping
	@Operation(summary = "Preview bulk input",
			description = "Parses pasted text or CSV and reports each line as NEW, DUPLICATE (of a word you have, "
					+ "or of an earlier line) or INVALID. Nothing is saved; confirm the lines to keep. A line without "
					+ "a part of speech counts as a duplicate of any word with the same lemma.")
	@ApiResponse(responseCode = "200", description = "One entry per non-blank line")
	@ApiResponse(responseCode = "409", description = "No active language yet (code NO_ACTIVE_LANGUAGE)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	PreviewResponse preview(@CurrentUserId UUID userId, @Valid @RequestBody PreviewRequest request) {
		Format format = request.format() == null ? Format.TEXT : request.format();
		return PreviewResponse.from(bulk.preview(userId, request.text(), format));
	}

	@PostMapping("/confirm")
	@Operation(summary = "Add the confirmed bulk items",
			description = "Adds each item to the active language on its own, so one failure doesn't stop the rest. "
					+ "Items that are duplicates by now are reported, not added. New words await AI enrichment.")
	@ApiResponse(responseCode = "200", description = "One result per item, in request order")
	@ApiResponse(responseCode = "409", description = "No active language yet (code NO_ACTIVE_LANGUAGE)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ConfirmResponse confirm(@CurrentUserId UUID userId, @Valid @RequestBody ConfirmRequest request) {
		List<BulkItem> items = request.items().stream().map(ItemRequest::toItem).toList();
		return ConfirmResponse.from(bulk.confirm(userId, items, request.tagIds()));
	}

}
