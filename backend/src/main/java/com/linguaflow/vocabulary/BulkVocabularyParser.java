package com.linguaflow.vocabulary;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.linguaflow.common.domain.CefrLevel;

/**
 * Turns pasted text into candidate words for bulk input (VOC-04). Pure and stateless; the
 * duplicate check happens in {@link BulkVocabularyService}.
 * <ul>
 * <li>{@link Format#TEXT}: one word or phrase per line, optionally {@code word - translation}.
 * The separator is the first {@code -} or {@code =} with spaces around it, an en or em
 * dash, or a tab, so hyphenated words like "E-Mail" stay whole.</li>
 * <li>{@link Format#CSV}: comma- or semicolon-separated (semicolon if the first line has at
 * least as many semicolons as commas),
 * quotes as in RFC 4180. Without a header the columns are lemma, translation. With a
 * header row (recognised by a "lemma" or "word" column) these columns may appear in any
 * order: lemma/word, translation, partOfSpeech/pos, cefrLevel/level, notes.</li>
 * </ul>
 * Blank lines are skipped; line numbers count them, so they match the user's input.
 */
public final class BulkVocabularyParser {

	public enum Format {

		TEXT, CSV

	}

	/** One candidate; {@code error} is set when it can't be added as it is. */
	public record ParsedLine(int lineNumber, String text, String lemma, String translation,
			PartOfSpeech partOfSpeech, CefrLevel cefrLevel, String notes, String error) {

		static ParsedLine invalid(int lineNumber, String text, String lemma, String error) {
			return new ParsedLine(lineNumber, text, lemma, null, null, null, null, error);
		}

	}

	public static final int MAX_LINES = 500;

	public static final int MAX_TRANSLATION_LENGTH = 500;

	public static final int MAX_NOTES_LENGTH = 2000;

	private static final Pattern TEXT_SEPARATOR = Pattern.compile("\\s+[-=]\\s+|\\t+|\\s*[–—]\\s*");

	private static final Map<String, Column> HEADER_NAMES = Map.of("lemma", Column.LEMMA, "word", Column.LEMMA,
			"translation", Column.TRANSLATION, "partofspeech", Column.PART_OF_SPEECH, "pos", Column.PART_OF_SPEECH,
			"cefrlevel", Column.CEFR_LEVEL, "level", Column.CEFR_LEVEL, "notes", Column.NOTES);

	private enum Column {

		LEMMA, TRANSLATION, PART_OF_SPEECH, CEFR_LEVEL, NOTES, IGNORED

	}

	private BulkVocabularyParser() {
	}

	/**
	 * Parses the input; blank lines produce nothing.
	 * @throws TooManyLinesException if there are more than {@link #MAX_LINES} candidates
	 */
	public static List<ParsedLine> parse(String input, Format format) {
		String text = input == null ? "" : input.replace("\r\n", "\n").replace('\r', '\n');
		if (!text.isEmpty() && text.charAt(0) == '﻿') {
			text = text.substring(1);
		}
		return format == Format.CSV ? parseCsv(text) : parseText(text);
	}

	/** More candidate lines than one request may carry. */
	public static class TooManyLinesException extends RuntimeException {

		TooManyLinesException() {
			super("at most " + MAX_LINES + " lines per request");
		}

	}

	private static List<ParsedLine> parseText(String text) {
		List<ParsedLine> result = new ArrayList<>();
		String[] lines = text.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			if (line.isBlank()) {
				continue;
			}
			checkCount(result);
			Matcher separator = TEXT_SEPARATOR.matcher(line);
			String lemma = line;
			String translation = null;
			if (separator.find()) {
				lemma = line.substring(0, separator.start());
				translation = line.substring(separator.end());
			}
			result.add(candidate(i + 1, line.strip(), lemma, translation, null, null, null));
		}
		return result;
	}

	private static List<ParsedLine> parseCsv(String text) {
		char delimiter = detectDelimiter(text);
		List<Record> records = readCsv(text, delimiter);
		List<ParsedLine> result = new ArrayList<>();
		List<Column> columns = null;
		int first = 0;
		if (!records.isEmpty()) {
			List<Column> header = header(records.get(0).cells());
			if (header != null) {
				columns = header;
				first = 1;
			}
		}
		for (int r = first; r < records.size(); r++) {
			Record record = records.get(r);
			List<String> cells = record.cells();
			if (cells.stream().allMatch(String::isBlank)) {
				continue;
			}
			checkCount(result);
			String raw = record.raw().strip();
			if (columns == null && cells.size() > 2) {
				result.add(ParsedLine.invalid(record.line(), raw, cells.get(0).strip(),
						"has " + cells.size() + " columns; add a header row (lemma, translation, partOfSpeech, "
								+ "cefrLevel, notes) to use more than two"));
				continue;
			}
			List<Column> layout = columns == null ? List.of(Column.LEMMA, Column.TRANSLATION) : columns;
			String lemma = null;
			String translation = null;
			String partOfSpeech = null;
			String cefrLevel = null;
			String notes = null;
			for (int c = 0; c < cells.size() && c < layout.size(); c++) {
				String cell = cells.get(c);
				switch (layout.get(c)) {
					case LEMMA -> lemma = cell;
					case TRANSLATION -> translation = cell;
					case PART_OF_SPEECH -> partOfSpeech = cell;
					case CEFR_LEVEL -> cefrLevel = cell;
					case NOTES -> notes = cell;
					case IGNORED -> {
					}
				}
			}
			result.add(candidate(record.line(), raw, lemma, translation, partOfSpeech, cefrLevel, notes));
		}
		return result;
	}

	private static ParsedLine candidate(int lineNumber, String text, String rawLemma, String rawTranslation,
			String rawPartOfSpeech, String rawCefrLevel, String rawNotes) {
		String lemma = clean(rawLemma);
		String translation = clean(rawTranslation);
		String notes = rawNotes == null || rawNotes.isBlank() ? null : rawNotes.strip();
		if (lemma == null) {
			return ParsedLine.invalid(lineNumber, text, null, "has no word");
		}
		if (lemma.length() > VocabularyService.MAX_LEMMA_LENGTH) {
			return ParsedLine.invalid(lineNumber, text, lemma,
					"word is longer than " + VocabularyService.MAX_LEMMA_LENGTH + " characters");
		}
		if (translation != null && translation.length() > MAX_TRANSLATION_LENGTH) {
			return ParsedLine.invalid(lineNumber, text, lemma,
					"translation is longer than " + MAX_TRANSLATION_LENGTH + " characters");
		}
		if (notes != null && notes.length() > MAX_NOTES_LENGTH) {
			return ParsedLine.invalid(lineNumber, text, lemma,
					"notes are longer than " + MAX_NOTES_LENGTH + " characters");
		}
		PartOfSpeech partOfSpeech = null;
		if (rawPartOfSpeech != null && !rawPartOfSpeech.isBlank()) {
			partOfSpeech = parseEnum(PartOfSpeech.class, rawPartOfSpeech);
			if (partOfSpeech == null) {
				return ParsedLine.invalid(lineNumber, text, lemma,
						"unknown part of speech '" + rawPartOfSpeech.strip() + "'");
			}
		}
		CefrLevel cefrLevel = null;
		if (rawCefrLevel != null && !rawCefrLevel.isBlank()) {
			cefrLevel = parseEnum(CefrLevel.class, rawCefrLevel);
			if (cefrLevel == null) {
				return ParsedLine.invalid(lineNumber, text, lemma, "unknown level '" + rawCefrLevel.strip() + "'");
			}
		}
		return new ParsedLine(lineNumber, text, lemma, translation, partOfSpeech, cefrLevel, notes, null);
	}

	private static String clean(String value) {
		if (value == null) {
			return null;
		}
		String cleaned = value.replaceAll("[\\s\\p{Z}]+", " ").strip();
		return cleaned.isEmpty() ? null : cleaned;
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
		String key = raw.strip().replace(' ', '_').replace('-', '_').toUpperCase(Locale.ROOT);
		for (E value : type.getEnumConstants()) {
			if (value.name().equals(key)) {
				return value;
			}
		}
		return null;
	}

	private static void checkCount(List<ParsedLine> result) {
		if (result.size() >= MAX_LINES) {
			throw new TooManyLinesException();
		}
	}

	/** The header's columns, or null if the first record isn't a header. */
	private static List<Column> header(List<String> cells) {
		List<Column> columns = new ArrayList<>();
		boolean hasLemma = false;
		for (String cell : cells) {
			String key = cell.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
			Column column = HEADER_NAMES.getOrDefault(key, Column.IGNORED);
			hasLemma |= column == Column.LEMMA;
			columns.add(column);
		}
		return hasLemma ? columns : null;
	}

	private static char detectDelimiter(String text) {
		int end = text.indexOf('\n');
		String firstLine = end < 0 ? text : text.substring(0, end);
		long semicolons = firstLine.chars().filter(c -> c == ';').count();
		long commas = firstLine.chars().filter(c -> c == ',').count();
		return semicolons > 0 && semicolons >= commas ? ';' : ',';
	}

	private record Record(int line, String raw, List<String> cells) {
	}

	/** RFC 4180 reader: quoted cells may hold delimiters, newlines and doubled quotes. */
	private static List<Record> readCsv(String text, char delimiter) {
		List<Record> records = new ArrayList<>();
		List<String> cells = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		boolean quoted = false;
		int line = 1;
		int recordLine = 1;
		int recordStart = 0;
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (quoted) {
				if (ch == '"') {
					if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
						cell.append('"');
						i++;
					}
					else {
						quoted = false;
					}
				}
				else {
					if (ch == '\n') {
						line++;
					}
					cell.append(ch);
				}
			}
			else if (ch == '"' && cell.toString().isBlank()) {
				cell.setLength(0);
				quoted = true;
			}
			else if (ch == delimiter) {
				cells.add(cell.toString());
				cell.setLength(0);
			}
			else if (ch == '\n') {
				cells.add(cell.toString());
				records.add(new Record(recordLine, text.substring(recordStart, i), cells));
				cells = new ArrayList<>();
				cell.setLength(0);
				line++;
				recordLine = line;
				recordStart = i + 1;
			}
			else {
				cell.append(ch);
			}
		}
		if (cell.length() > 0 || !cells.isEmpty()) {
			cells.add(cell.toString());
			records.add(new Record(recordLine, text.substring(recordStart), cells));
		}
		return records;
	}

}
