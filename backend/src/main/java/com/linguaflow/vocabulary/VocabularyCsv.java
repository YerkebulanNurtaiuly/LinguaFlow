package com.linguaflow.vocabulary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.vocabulary.BulkVocabularyParser.Record;

/**
 * The CSV format of vocabulary export and import (VOC-07). Export writes every column
 * below; import reads any of them, in any order, by the header row (which it requires).
 * Tags are names separated by {@code |}. Cells that a spreadsheet would run as a formula
 * (starting with {@code = + - @}) are written with a leading {@code '}, which import
 * removes again, so a file round-trips unchanged. Examples, AI data and dates are not part
 * of the file.
 */
public final class VocabularyCsv {

	public static final List<String> COLUMNS = List.of("lemma", "translation", "partOfSpeech", "gender", "plural",
			"cefrLevel", "status", "notes", "tags");

	public static final int MAX_PLURAL_LENGTH = 200;

	static final String TAG_SEPARATOR = "|";

	private static final Map<String, String> HEADER_ALIASES = Map.of("word", "lemma", "pos", "partOfSpeech",
			"level", "cefrLevel");

	/** One data row; {@code error} is set when it can't be imported as it is. */
	public record ImportRow(int line, String lemma, String translation, PartOfSpeech partOfSpeech, Gender gender,
			String plural, CefrLevel cefrLevel, KnowledgeStatus status, String notes, List<String> tags,
			String error) {

		static ImportRow invalid(int line, String lemma, String error) {
			return new ImportRow(line, lemma, null, null, null, null, null, null, null, List.of(), error);
		}

	}

	private VocabularyCsv() {
	}

	// Export -------------------------------------------------------------------------------

	/** Header plus one row per item, CRLF line endings as in RFC 4180. */
	public static String write(List<VocabularyItem> items) {
		StringBuilder out = new StringBuilder();
		appendRow(out, COLUMNS);
		for (VocabularyItem item : items) {
			appendRow(out,
					List.of(item.getLemma(), orEmpty(item.getTranslation()), name(item.getPartOfSpeech()),
							name(item.getGender()), orEmpty(item.getPlural()), name(item.getCefrLevel()),
							name(item.getStatus()), orEmpty(item.getNotes()),
							item.getTags()
								.stream()
								.map(Tag::getName)
								.sorted(String.CASE_INSENSITIVE_ORDER)
								.collect(Collectors.joining(TAG_SEPARATOR))));
		}
		return out.toString();
	}

	private static void appendRow(StringBuilder out, List<String> cells) {
		for (int i = 0; i < cells.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			out.append(escape(cells.get(i)));
		}
		out.append("\r\n");
	}

	static String escape(String value) {
		String cell = value;
		if (!cell.isEmpty() && "=+-@\t\r".indexOf(cell.charAt(0)) >= 0) {
			cell = "'" + cell;
		}
		boolean quote = cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r")
				|| cell.contains(";") || !cell.equals(cell.strip());
		return quote ? '"' + cell.replace("\"", "\"\"") + '"' : cell;
	}

	private static String name(Enum<?> value) {
		return value == null ? "" : value.name();
	}

	private static String orEmpty(String value) {
		return value == null ? "" : value;
	}

	// Import -------------------------------------------------------------------------------

	/**
	 * Reads the rows of an import file; blank rows are skipped.
	 * @throws InvalidFieldException on "file" if there is no header with a lemma column or
	 * more than {@code maxRows} rows
	 */
	public static List<ImportRow> read(String input, int maxRows) {
		String text = input == null ? "" : input.replace("\r\n", "\n").replace('\r', '\n');
		if (!text.isEmpty() && text.charAt(0) == '﻿') {
			text = text.substring(1);
		}
		List<Record> records = BulkVocabularyParser.readCsv(text, BulkVocabularyParser.detectDelimiter(text));
		if (records.isEmpty()) {
			throw new InvalidFieldException("file", "is empty");
		}
		List<String> header = records.getFirst().cells().stream().map(VocabularyCsv::headerName).toList();
		if (!header.contains("lemma")) {
			throw new InvalidFieldException("file",
					"needs a header row with a lemma column; known columns: " + String.join(", ", COLUMNS));
		}
		List<ImportRow> rows = new ArrayList<>();
		for (Record record : records.subList(1, records.size())) {
			if (record.cells().stream().allMatch(String::isBlank)) {
				continue;
			}
			if (rows.size() >= maxRows) {
				throw new InvalidFieldException("file", "has more than " + maxRows + " rows; split it into parts");
			}
			rows.add(row(record, header));
		}
		return rows;
	}

	private static String headerName(String cell) {
		String key = cell.strip().replaceAll("[\\s_-]", "");
		for (String column : COLUMNS) {
			if (column.equalsIgnoreCase(key)) {
				return column;
			}
		}
		return HEADER_ALIASES.getOrDefault(key.toLowerCase(Locale.ROOT), "");
	}

	private static ImportRow row(Record record, List<String> header) {
		Map<String, String> cells = new java.util.HashMap<>();
		for (int c = 0; c < record.cells().size() && c < header.size(); c++) {
			if (!header.get(c).isEmpty()) {
				cells.putIfAbsent(header.get(c), unguard(record.cells().get(c)));
			}
		}
		int line = record.line();
		String lemma = BulkVocabularyParser.clean(cells.get("lemma"));
		if (lemma == null) {
			return ImportRow.invalid(line, null, "has no word");
		}
		if (lemma.length() > VocabularyService.MAX_LEMMA_LENGTH) {
			return ImportRow.invalid(line, lemma,
					"word is longer than " + VocabularyService.MAX_LEMMA_LENGTH + " characters");
		}
		String translation = BulkVocabularyParser.clean(cells.get("translation"));
		if (translation != null && translation.length() > BulkVocabularyParser.MAX_TRANSLATION_LENGTH) {
			return ImportRow.invalid(line, lemma,
					"translation is longer than " + BulkVocabularyParser.MAX_TRANSLATION_LENGTH + " characters");
		}
		String plural = BulkVocabularyParser.clean(cells.get("plural"));
		if (plural != null && plural.length() > MAX_PLURAL_LENGTH) {
			return ImportRow.invalid(line, lemma, "plural is longer than " + MAX_PLURAL_LENGTH + " characters");
		}
		String notes = cells.get("notes") == null || cells.get("notes").isBlank() ? null : cells.get("notes").strip();
		if (notes != null && notes.length() > BulkVocabularyParser.MAX_NOTES_LENGTH) {
			return ImportRow.invalid(line, lemma,
					"notes are longer than " + BulkVocabularyParser.MAX_NOTES_LENGTH + " characters");
		}
		Parsed<PartOfSpeech> partOfSpeech = enumCell(PartOfSpeech.class, cells.get("partOfSpeech"), "part of speech");
		Parsed<Gender> gender = enumCell(Gender.class, cells.get("gender"), "gender");
		Parsed<CefrLevel> cefrLevel = enumCell(CefrLevel.class, cells.get("cefrLevel"), "level");
		Parsed<KnowledgeStatus> status = enumCell(KnowledgeStatus.class, cells.get("status"), "status");
		for (Parsed<?> parsed : List.of(partOfSpeech, gender, cefrLevel, status)) {
			if (parsed.error() != null) {
				return ImportRow.invalid(line, lemma, parsed.error());
			}
		}
		List<String> tags;
		try {
			tags = tags(cells.get("tags"));
		}
		catch (InvalidFieldException ex) {
			return ImportRow.invalid(line, lemma, "tag " + ex.getMessage());
		}
		if (tags.size() > TagService.MAX_TAGS_PER_WORD) {
			return ImportRow.invalid(line, lemma, "has more than " + TagService.MAX_TAGS_PER_WORD + " tags");
		}
		return new ImportRow(line, lemma, translation, partOfSpeech.value(), gender.value(), plural,
				cefrLevel.value(), status.value(), notes, tags, null);
	}

	/** Undoes the formula guard written by {@link #escape}. */
	static String unguard(String cell) {
		if (cell.length() > 1 && cell.charAt(0) == '\'' && "=+-@\t\r".indexOf(cell.charAt(1)) >= 0) {
			return cell.substring(1);
		}
		return cell;
	}

	private static List<String> tags(String cell) {
		if (cell == null || cell.isBlank()) {
			return List.of();
		}
		Set<String> lowerSeen = new LinkedHashSet<>();
		List<String> names = new ArrayList<>();
		for (String part : cell.split("\\|")) {
			if (part.isBlank()) {
				continue;
			}
			String name = TagService.cleanName(part);
			if (lowerSeen.add(name.toLowerCase(Locale.ROOT))) {
				names.add(name);
			}
		}
		names.sort(Comparator.comparing(String::toLowerCase));
		return names;
	}

	private record Parsed<E>(E value, String error) {
	}

	private static <E extends Enum<E>> Parsed<E> enumCell(Class<E> type, String raw, String label) {
		if (raw == null || raw.isBlank()) {
			return new Parsed<>(null, null);
		}
		E value = BulkVocabularyParser.parseEnum(type, raw);
		return value == null ? new Parsed<>(null, "unknown " + label + " '" + raw.strip() + "'")
				: new Parsed<>(value, null);
	}

}
