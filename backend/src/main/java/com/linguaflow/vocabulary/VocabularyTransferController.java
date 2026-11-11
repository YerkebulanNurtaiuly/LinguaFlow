package com.linguaflow.vocabulary;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.common.error.ApiProblem;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.common.security.CurrentUserId;
import com.linguaflow.vocabulary.VocabularyTransferService.ImportResult;
import com.linguaflow.vocabulary.VocabularyTransferService.RowResult;
import com.linguaflow.vocabulary.VocabularyTransferService.RowStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** CSV export and import of vocabulary (VOC-07). The format is described on {@link VocabularyCsv}. */
@RestController
@RequestMapping("/api/vocabulary")
@Tag(name = "Vocabulary", description = "The learner's words and phrases")
class VocabularyTransferController {

	static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

	private final VocabularyTransferService transfer;

	private final Clock clock;

	VocabularyTransferController(VocabularyTransferService transfer, Clock clock) {
		this.transfer = transfer;
		this.clock = clock;
	}

	@Schema(name = "VocabularyImportRow")
	record RowResponse(@Schema(description = "1-based line in the file") int line, String lemma, RowStatus status,
			@Schema(description = "CREATED: the new word's ID") UUID id,
			@Schema(description = "DUPLICATE: the word you already have") UUID existingId,
			@Schema(description = "Why the row is DUPLICATE or INVALID, or what was changed on import")
			String message) {

		static RowResponse from(RowResult row) {
			return new RowResponse(row.line(), row.lemma(), row.status(), row.id(), row.existingId(), row.message());
		}

	}

	@Schema(name = "VocabularyImportResult")
	record ImportResponse(List<RowResponse> rows, int createdCount, int duplicateCount, int invalidCount,
			@Schema(description = "Tags the file named that you didn't have yet") int createdTagCount,
			@Schema(description = "AI jobs enriching the imported words, in batches") List<UUID> aiJobIds) {

		static ImportResponse from(ImportResult result) {
			return new ImportResponse(result.rows().stream().map(RowResponse::from).toList(),
					count(result, RowStatus.CREATED), count(result, RowStatus.DUPLICATE),
					count(result, RowStatus.INVALID), result.createdTagCount(), result.aiJobIds());
		}

		private static int count(ImportResult result, RowStatus status) {
			return (int) result.rows().stream().filter(r -> r.status() == status).count();
		}

	}

	@GetMapping(path = "/export", produces = "text/csv")
	@Operation(summary = "Export vocabulary as CSV",
			description = "Words of the active language, oldest first, with the same filters as the list. Columns: "
					+ "lemma, translation, partOfSpeech, gender, plural, cefrLevel, status, notes, tags (names "
					+ "separated by |). UTF-8 with a byte order mark, so spreadsheets detect the encoding.")
	@ApiResponse(responseCode = "200", description = "The CSV file",
			content = @Content(mediaType = "text/csv", schema = @Schema(type = "string")))
	@ApiResponse(responseCode = "409", description = "No active language yet (code NO_ACTIVE_LANGUAGE)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<byte[]> export(@CurrentUserId UUID userId,
			@Parameter(description = "Text in the lemma or the translation") @RequestParam(name = "q",
					required = false) String query,
			@RequestParam(required = false) List<PartOfSpeech> partOfSpeech,
			@RequestParam(required = false) List<CefrLevel> cefrLevel,
			@RequestParam(required = false) List<KnowledgeStatus> status,
			@RequestParam(required = false) UUID sessionId, @RequestParam(required = false) UUID tagId) {
		if (query != null && query.length() > 200) {
			throw new InvalidFieldException("q", "size must be between 0 and 200");
		}
		var export = transfer.export(userId, new VocabularyFilter(query, toSet(partOfSpeech), toSet(cefrLevel),
				toSet(status), sessionId, tagId));
		String filename = "linguaflow-vocabulary-" + export.languageCode() + "-" + LocalDate.now(clock) + ".csv";
		return ResponseEntity.ok()
			.contentType(TEXT_CSV)
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
			.body(("﻿" + export.csv()).getBytes(StandardCharsets.UTF_8));
	}

	@PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Import vocabulary from CSV",
			description = "A UTF-8 CSV file (comma or semicolon) with a header row naming any of the export's "
					+ "columns; lemma is required. Adds each row to the active language on its own and reports it. "
					+ "Words you already have (same lemma and part of speech) are skipped; tags are matched by name "
					+ "and created when missing; MASTERED is imported as LEARNED. At most 2000 rows and 2 MB.")
	@ApiResponse(responseCode = "200", description = "One result per data row, in file order")
	@ApiResponse(responseCode = "409", description = "No active language yet (code NO_ACTIVE_LANGUAGE)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ImportResponse importCsv(@CurrentUserId UUID userId,
			@Parameter(description = "The CSV file") @RequestPart("file") MultipartFile file) throws IOException {
		if (file.isEmpty()) {
			throw new InvalidFieldException("file", "is empty");
		}
		return ImportResponse.from(transfer.importCsv(userId, decodeUtf8(file.getBytes())));
	}

	private static String decodeUtf8(byte[] bytes) {
		try {
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes))
				.toString();
		}
		catch (CharacterCodingException ex) {
			throw new InvalidFieldException("file", "must be UTF-8 text");
		}
	}

	private static <T> Set<T> toSet(List<T> values) {
		return values == null ? Set.of() : Set.copyOf(values);
	}

}
