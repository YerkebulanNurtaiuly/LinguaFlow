package com.linguaflow.vocabulary;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.user.ActiveLanguageService;
import com.linguaflow.vocabulary.VocabularyCsv.ImportRow;
import com.linguaflow.vocabulary.VocabularyCommands.NewVocabularyItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * CSV export and import of vocabulary (VOC-07), in the {@link VocabularyCsv} format.
 * <ul>
 * <li>Export: the active language's words matching a filter, oldest first.</li>
 * <li>Import: adds every valid row to the active language on its own and reports each
 * row. Tags are matched by name and created when missing. Unlike bulk input it uses the
 * exact duplicate rule (same normalized lemma and part of speech), because files carry
 * parts of speech and an exported file must import back completely. MASTERED is earned, so
 * it is imported as LEARNED.</li>
 * </ul>
 */
@Service
public class VocabularyTransferService {

	public static final int MAX_IMPORT_ROWS = 2000;

	/** Most distinct tag names one file may use. */
	public static final int MAX_IMPORT_TAGS = 200;

	private static final int EXPORT_PAGE_SIZE = 500;

	public enum RowStatus {

		CREATED, DUPLICATE, INVALID

	}

	/** The outcome for one data row; {@code message} explains DUPLICATE, INVALID or a change. */
	public record RowResult(int line, String lemma, RowStatus status, UUID id, UUID existingId, String message) {
	}

	public record ImportResult(List<RowResult> rows, int createdTagCount) {
	}

	public record Export(String languageCode, String csv, int count) {
	}

	private final VocabularyService vocabulary;

	private final TagService tags;

	private final TagRepository tagRepository;

	private final ActiveLanguageService activeLanguages;

	VocabularyTransferService(VocabularyService vocabulary, TagService tags, TagRepository tagRepository,
			ActiveLanguageService activeLanguages) {
		this.vocabulary = vocabulary;
		this.tags = tags;
		this.tagRepository = tagRepository;
		this.activeLanguages = activeLanguages;
	}

	public Export export(UUID userId, VocabularyFilter filter) {
		String language = activeLanguages.requireActive(userId);
		List<VocabularyItem> all = new ArrayList<>();
		Page<VocabularyItem> page;
		int number = 0;
		do {
			page = vocabulary.list(userId, filter,
					PageRequest.of(number++, EXPORT_PAGE_SIZE, Sort.by(Sort.Direction.ASC, "createdAt")));
			all.addAll(page.getContent());
		}
		while (page.hasNext());
		return new Export(language, VocabularyCsv.write(all), all.size());
	}

	/**
	 * Imports a file's rows into the active language.
	 * @throws InvalidFieldException on "file" if it has no usable header, too many rows or
	 * too many tags; nothing is imported then
	 */
	public ImportResult importCsv(UUID userId, String csv) {
		String language = activeLanguages.requireActive(userId);
		List<ImportRow> rows = VocabularyCsv.read(csv, MAX_IMPORT_ROWS);

		Set<String> tagNames = new LinkedHashSet<>();
		Set<String> lowerNames = new LinkedHashSet<>();
		rows.stream().filter(r -> r.error() == null).flatMap(r -> r.tags().stream()).forEach(name -> {
			if (lowerNames.add(name.toLowerCase(Locale.ROOT))) {
				tagNames.add(name);
			}
		});
		if (tagNames.size() > MAX_IMPORT_TAGS) {
			throw new InvalidFieldException("file", "uses more than " + MAX_IMPORT_TAGS + " different tags");
		}
		long tagsBefore = tagRepository.countByUserId(userId);
		Map<String, UUID> tagIds = tags.findOrCreate(userId, language, tagNames);
		int createdTags = (int) (tagRepository.countByUserId(userId) - tagsBefore);

		List<RowResult> results = new ArrayList<>();
		for (ImportRow row : rows) {
			if (row.error() != null) {
				results.add(new RowResult(row.line(), row.lemma(), RowStatus.INVALID, null, null, row.error()));
				continue;
			}
			KnowledgeStatus status = row.status();
			String note = null;
			if (status == KnowledgeStatus.MASTERED) {
				status = KnowledgeStatus.LEARNED;
				note = "imported as LEARNED; mastery is earned through practice";
			}
			List<UUID> rowTags = row.tags().stream().map(name -> tagIds.get(name.toLowerCase(Locale.ROOT))).toList();
			try {
				VocabularyItem created = vocabulary.create(userId,
						new NewVocabularyItem(row.lemma(), row.partOfSpeech(), row.translation(), row.gender(),
								row.plural(), row.cefrLevel(), status, row.notes(), List.of(), rowTags),
						VocabularyService.DuplicateRule.EXACT);
				results.add(new RowResult(row.line(), row.lemma(), RowStatus.CREATED, created.getId(), null, note));
			}
			catch (DuplicateVocabularyItemException ex) {
				results.add(new RowResult(row.line(), row.lemma(), RowStatus.DUPLICATE, null, ex.existingId(),
						"already in your vocabulary"));
			}
			catch (InvalidFieldException ex) {
				results.add(new RowResult(row.line(), row.lemma(), RowStatus.INVALID, null, null,
						ex.field() + " " + ex.getMessage()));
			}
		}
		return new ImportResult(results, createdTags);
	}

}
