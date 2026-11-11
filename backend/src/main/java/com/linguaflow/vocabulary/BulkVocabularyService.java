package com.linguaflow.vocabulary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.user.ActiveLanguageService;
import com.linguaflow.vocabulary.BulkVocabularyParser.Format;
import com.linguaflow.vocabulary.BulkVocabularyParser.ParsedLine;
import com.linguaflow.vocabulary.VocabularyCommands.NewVocabularyItem;
import org.springframework.stereotype.Service;

/**
 * Bulk vocabulary input (VOC-04) in two steps: {@link #preview} parses pasted text and
 * says per line whether it's new, a duplicate or invalid, without saving anything;
 * {@link #confirm} adds the items the learner kept, each on its own, so one failure
 * doesn't block the rest.
 * <p>
 * Duplicates follow the same rule as adding one word ({@link VocabularyService.DuplicateRule#SAME_WORD}):
 * same normalized lemma, and the same part of speech <em>or</em> none on either side.
 * "Hund" is a duplicate of your "der Hund" (noun).
 */
@Service
public class BulkVocabularyService {

	public static final int MAX_ITEMS = BulkVocabularyParser.MAX_LINES;

	public enum LineStatus {

		NEW, DUPLICATE, INVALID

	}

	public enum ItemStatus {

		CREATED, DUPLICATE, INVALID

	}

	/**
	 * A previewed line. For DUPLICATE, {@code existingId} points at your word or
	 * {@code duplicateOfLine} at an earlier line of the same input.
	 */
	public record PreviewLine(ParsedLine parsed, LineStatus status, UUID existingId, Integer duplicateOfLine,
			String message) {
	}

	/** An item to add, as the learner confirmed it. */
	public record BulkItem(String lemma, String translation, PartOfSpeech partOfSpeech, CefrLevel cefrLevel,
			String notes) {
	}

	/**
	 * What happened to one confirmed item: CREATED with its {@code id}, DUPLICATE with
	 * {@code existingId} (your word, or the one an earlier item just created), or INVALID
	 * with the {@code field} and {@code message}.
	 */
	/** The outcome per item, and the AI jobs enriching the new words (VOC-05, AI-08). */
	public record ConfirmResult(List<ItemResult> results, List<UUID> aiJobIds) {
	}

	public record ItemResult(int index, String lemma, ItemStatus status, UUID id, UUID existingId, String field,
			String message) {
	}

	private final VocabularyItemRepository items;

	private final VocabularyService vocabulary;

	private final TagService tags;

	private final ActiveLanguageService activeLanguages;

	private final EnrichmentQueue enrichment;

	BulkVocabularyService(VocabularyItemRepository items, VocabularyService vocabulary, TagService tags,
			ActiveLanguageService activeLanguages, EnrichmentQueue enrichment) {
		this.enrichment = enrichment;
		this.items = items;
		this.vocabulary = vocabulary;
		this.tags = tags;
		this.activeLanguages = activeLanguages;
	}

	/** Parses and checks the input; nothing is saved. */
	public List<PreviewLine> preview(UUID userId, String text, Format format) {
		String language = activeLanguages.requireActive(userId);
		List<ParsedLine> lines;
		try {
			lines = BulkVocabularyParser.parse(text, format);
		}
		catch (BulkVocabularyParser.TooManyLinesException ex) {
			throw new InvalidFieldException("text", ex.getMessage());
		}
		Map<String, List<ExistingLemma>> existing = existingByLemma(userId, language,
				lines.stream().filter(l -> l.error() == null).map(l -> LemmaNormalizer.normalize(language, l.lemma())));

		List<PreviewLine> result = new ArrayList<>();
		Map<String, List<ParsedLine>> earlier = new HashMap<>();
		for (ParsedLine line : lines) {
			if (line.error() != null) {
				result.add(new PreviewLine(line, LineStatus.INVALID, null, null, line.error()));
				continue;
			}
			String normalized = LemmaNormalizer.normalize(language, line.lemma());
			Optional<ExistingLemma> owned = existing.getOrDefault(normalized, List.of())
				.stream()
				.filter(e -> sameWord(e.partOfSpeech(), line.partOfSpeech()))
				.findFirst();
			Optional<ParsedLine> repeated = earlier.getOrDefault(normalized, List.of())
				.stream()
				.filter(e -> sameWord(e.partOfSpeech(), line.partOfSpeech()))
				.findFirst();
			if (owned.isPresent()) {
				result.add(new PreviewLine(line, LineStatus.DUPLICATE, owned.get().id(), null,
						"already in your vocabulary"));
			}
			else if (repeated.isPresent()) {
				result.add(new PreviewLine(line, LineStatus.DUPLICATE, null, repeated.get().lineNumber(),
						"repeats line " + repeated.get().lineNumber()));
			}
			else {
				result.add(new PreviewLine(line, LineStatus.NEW, null, null, null));
			}
			earlier.computeIfAbsent(normalized, k -> new ArrayList<>()).add(line);
		}
		return result;
	}

	/**
	 * Adds the items to the active language, optionally all with the same tags.
	 * @throws InvalidFieldException on "tagIds" if a tag isn't usable; nothing is added then
	 */
	public ConfirmResult confirm(UUID userId, List<BulkItem> bulkItems, List<UUID> tagIds) {
		String language = activeLanguages.requireActive(userId);
		List<UUID> wantedTags = tagIds == null ? List.of() : List.copyOf(tagIds);
		tags.checkUsable(userId, language, wantedTags);
		Map<String, List<ExistingLemma>> existing = existingByLemma(userId, language, bulkItems.stream()
			.filter(i -> i.lemma() != null && !i.lemma().isBlank())
			.map(i -> LemmaNormalizer.normalize(language, i.lemma())));

		List<ItemResult> results = new ArrayList<>();
		for (int index = 0; index < bulkItems.size(); index++) {
			BulkItem item = bulkItems.get(index);
			String lemma = item.lemma() == null ? null : item.lemma().strip();
			if (lemma == null || lemma.isEmpty()) {
				results.add(new ItemResult(index, lemma, ItemStatus.INVALID, null, null, "lemma", "must not be blank"));
				continue;
			}
			String normalized = LemmaNormalizer.normalize(language, lemma);
			List<ExistingLemma> sameLemma = existing.computeIfAbsent(normalized, k -> new ArrayList<>());
			Optional<ExistingLemma> duplicate = sameLemma.stream()
				.filter(e -> sameWord(e.partOfSpeech(), item.partOfSpeech()))
				.findFirst();
			if (duplicate.isPresent()) {
				results.add(new ItemResult(index, lemma, ItemStatus.DUPLICATE, null, duplicate.get().id(), null,
						"already in your vocabulary"));
				continue;
			}
			try {
				VocabularyItem created = vocabulary.create(userId, new NewVocabularyItem(lemma, item.partOfSpeech(),
						item.translation(), null, null, item.cefrLevel(), null, item.notes(), List.of(), wantedTags),
						VocabularyService.DuplicateRule.SAME_WORD, false);
				sameLemma.add(new ExistingLemma(created.getId(), created.getNormalizedLemma(),
						created.getPartOfSpeech()));
				results.add(new ItemResult(index, lemma, ItemStatus.CREATED, created.getId(), null, null, null));
			}
			catch (DuplicateVocabularyItemException ex) {
				results.add(new ItemResult(index, lemma, ItemStatus.DUPLICATE, null, ex.existingId(), null,
						"already in your vocabulary"));
			}
			catch (InvalidFieldException ex) {
				results.add(new ItemResult(index, lemma, ItemStatus.INVALID, null, null, ex.field(), ex.getMessage()));
			}
		}
		List<UUID> created = results.stream()
			.filter(result -> result.status() == ItemStatus.CREATED)
			.map(ItemResult::id)
			.toList();
		// One AI job per batch of new words (VOC-05), rather than one per word
		return new ConfirmResult(results, created.isEmpty() ? List.of() : enrichment.enqueue(userId, created));
	}

	/** Same word for bulk purposes: parts of speech match, or one side has none. */
	static boolean sameWord(PartOfSpeech a, PartOfSpeech b) {
		return a == null || b == null || a == b;
	}

	private Map<String, List<ExistingLemma>> existingByLemma(UUID userId, String language,
			java.util.stream.Stream<String> normalizedLemmas) {
		Set<String> wanted = normalizedLemmas.collect(Collectors.toSet());
		if (wanted.isEmpty()) {
			return new HashMap<>();
		}
		return items.findExistingLemmas(userId, language, wanted)
			.stream()
			.collect(Collectors.groupingBy(ExistingLemma::normalizedLemma, HashMap::new,
					Collectors.toCollection(ArrayList::new)));
	}

}
