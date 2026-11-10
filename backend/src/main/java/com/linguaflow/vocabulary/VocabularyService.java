package com.linguaflow.vocabulary;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.common.error.NotFoundException;
import com.linguaflow.user.ActiveLanguageService;
import com.linguaflow.vocabulary.VocabularyCommands.NewExample;
import com.linguaflow.vocabulary.VocabularyCommands.NewVocabularyItem;
import com.linguaflow.vocabulary.VocabularyCommands.VocabularyItemChanges;
import org.hibernate.Hibernate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Business rules for a learner's vocabulary (VOC-01). Every operation is scoped to the
 * caller; new words go into their active language.
 * <ul>
 * <li>Duplicates: a word is a duplicate when its {@link LemmaNormalizer normalized
 * lemma} matches one the user already has in that language and so does the part of
 * speech, where a missing part of speech on either side matches any (schema.md Q2): "Hund"
 * is a duplicate of the noun "der Hund", while the verb "laufen" and the noun "das Laufen"
 * coexist. They are rejected with {@link DuplicateVocabularyItemException} (409, with the
 * existing ID), also when two requests race past the check and hit the unique
 * constraint. CSV import uses {@link DuplicateRule#EXACT} instead.</li>
 * <li>MASTERED can't be set by the learner; it is earned (PRG-02).</li>
 * <li>Gender only applies to nouns.</li>
 * <li>A field the learner edits no longer counts as AI-generated (VOC-05).</li>
 * <li>Tags (VOC-03) must be the user's own, in the word's language.</li>
 * </ul>
 * Returned items have their examples and tags loaded, so they can be mapped outside a
 * transaction (open-in-view is off).
 */
@Service
public class VocabularyService {

	public static final int MAX_LEMMA_LENGTH = 200;

	public static final int MAX_EXAMPLE_LENGTH = 1000;

	/** How a new word is compared with the ones the user has. */
	public enum DuplicateRule {

		/** Same lemma, and the same part of speech or none on either side (the default). */
		SAME_WORD,

		/**
		 * Same lemma and part of speech, nulls matching only nulls: exactly the unique key.
		 * For CSV import, where files carry parts of speech and must import back completely.
		 */
		EXACT

	}

	private final VocabularyItemRepository items;

	private final ActiveLanguageService activeLanguages;

	private final TagService tags;

	private final TransactionTemplate transactions;

	VocabularyService(VocabularyItemRepository items, ActiveLanguageService activeLanguages, TagService tags,
			PlatformTransactionManager transactionManager) {
		this.items = items;
		this.tags = tags;
		this.activeLanguages = activeLanguages;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/**
	 * Adds a word to the user's active language.
	 * @throws DuplicateVocabularyItemException if the user already has it
	 * @throws InvalidFieldException for an invalid lemma, status, gender or example
	 * @throws com.linguaflow.common.error.ApiException NO_ACTIVE_LANGUAGE before onboarding
	 */
	public VocabularyItem create(UUID userId, NewVocabularyItem request) {
		return create(userId, request, DuplicateRule.SAME_WORD);
	}

	/** Like {@link #create(UUID, NewVocabularyItem)}, with the given duplicate rule. */
	public VocabularyItem create(UUID userId, NewVocabularyItem request, DuplicateRule rule) {
		String language = activeLanguages.requireActive(userId);
		String lemma = cleanLemma(request.lemma());
		String normalized = LemmaNormalizer.normalize(language, lemma);
		checkGender(request.gender(), request.partOfSpeech());
		KnowledgeStatus status = request.status() == null ? KnowledgeStatus.LEARNING : request.status();
		checkStatus(status);

		DuplicateKey key = new DuplicateKey(userId, language, normalized, request.partOfSpeech());
		rejectDuplicate(key, lemma, null, rule);

		VocabularyItem item = new VocabularyItem(userId, language, lemma, normalized);
		item.setPartOfSpeech(request.partOfSpeech());
		item.setTranslation(blankToNull(request.translation()));
		item.setGender(request.gender());
		item.setPlural(blankToNull(request.plural()));
		item.setCefrLevel(request.cefrLevel());
		item.changeStatusByUser(status);
		item.setNotes(blankToNull(request.notes()));
		for (int i = 0; i < request.examples().size(); i++) {
			NewExample example = request.examples().get(i);
			item.addExample(cleanExample(example.sentence(), i), blankToNull(example.translation()), false);
		}
		return saveRejectingDuplicates(() -> transactions.execute(tx -> {
			item.replaceTags(tags.resolveForWord(userId, language, request.tagIds()));
			return items.saveAndFlush(item);
		}), () -> key, () -> lemma);
	}

	/** The user's word, or 404 if it doesn't exist or belongs to someone else. */
	@Transactional(readOnly = true)
	public VocabularyItem get(UUID userId, UUID id) {
		return loaded(items.requireOwned(id, userId));
	}

	/**
	 * One page of the user's words in their active language that match {@code filter}.
	 * Sort properties must be VocabularyItem attributes.
	 */
	@Transactional(readOnly = true)
	public Page<VocabularyItem> list(UUID userId, VocabularyFilter filter, Pageable pageable) {
		String language = activeLanguages.requireActive(userId);
		Page<VocabularyItem> page = items.search(userId, language, filter, pageable);
		page.forEach(VocabularyService::loaded);
		return page;
	}

	/**
	 * Applies the learner's partial update. Changing the lemma or part of speech is checked
	 * for duplicates again (the item itself doesn't count).
	 */
	public VocabularyItem update(UUID userId, UUID id, VocabularyItemChanges changes) {
		DuplicateKey[] key = new DuplicateKey[1];
		String[] lemmaHolder = new String[1];
		return saveRejectingDuplicates(() -> transactions.execute(tx -> {
			VocabularyItem item = items.requireOwned(id, userId);
			String lemma = changes.lemma() == null ? item.getLemma() : cleanLemma(changes.lemma());
			PartOfSpeech partOfSpeech = changes.partOfSpeech() == null ? item.getPartOfSpeech()
					: changes.partOfSpeech();
			String normalized = LemmaNormalizer.normalize(item.getLanguageCode(), lemma);
			lemmaHolder[0] = lemma;
			key[0] = new DuplicateKey(userId, item.getLanguageCode(), normalized, partOfSpeech);
			checkGender(changes.gender(), partOfSpeech);
			if (changes.status() != null) {
				checkStatus(changes.status());
			}
			if (changes.lemma() != null || changes.partOfSpeech() != null) {
				rejectDuplicate(key[0], lemma, item.getId(), DuplicateRule.SAME_WORD);
			}

			if (changes.lemma() != null) {
				item.rename(lemma, normalized);
			}
			edit(item, AiFields.PART_OF_SPEECH, changes.partOfSpeech(), item::setPartOfSpeech);
			edit(item, AiFields.TRANSLATION, changes.translation(), value -> item.setTranslation(blankToNull(value)));
			edit(item, AiFields.GENDER, changes.gender(), item::setGender);
			if (partOfSpeech != null && partOfSpeech != PartOfSpeech.NOUN && item.getGender() != null) {
				// No longer a noun, so the gender no longer applies
				item.setGender(null);
				item.markEditedByUser(AiFields.GENDER);
			}
			edit(item, AiFields.PLURAL, changes.plural(), value -> item.setPlural(blankToNull(value)));
			edit(item, AiFields.CEFR_LEVEL, changes.cefrLevel(), item::setCefrLevel);
			if (changes.status() != null) {
				item.changeStatusByUser(changes.status());
			}
			if (changes.notes() != null) {
				item.setNotes(blankToNull(changes.notes()));
			}
			if (changes.tagIds() != null) {
				item.replaceTags(tags.resolveForWord(userId, item.getLanguageCode(), changes.tagIds()));
			}
			return loaded(items.saveAndFlush(item));
		}), () -> key[0], () -> lemmaHolder[0]);
	}

	/** Deletes the word and everything attached to it (examples, flashcards, progress). */
	@Transactional
	public void delete(UUID userId, UUID id) {
		if (items.deleteByIdAndUserId(id, userId) == 0) {
			throw new NotFoundException("Resource not found");
		}
	}

	/** Names used in {@code ai_generated_fields}; AI enrichment (VOC-05) writes the same names. */
	public static final class AiFields {

		public static final String PART_OF_SPEECH = "partOfSpeech";

		public static final String TRANSLATION = "translation";

		public static final String GENDER = "gender";

		public static final String PLURAL = "plural";

		public static final String CEFR_LEVEL = "cefrLevel";

		private AiFields() {
		}

	}

	private record DuplicateKey(UUID userId, String languageCode, String normalizedLemma,
			PartOfSpeech partOfSpeech) {
	}

	private static <T> void edit(VocabularyItem item, String field, T value, Consumer<T> setter) {
		if (value != null) {
			setter.accept(value);
			item.markEditedByUser(field);
		}
	}

	private void rejectDuplicate(DuplicateKey key, String lemma, UUID self, DuplicateRule rule) {
		List<UUID> matches = rule == DuplicateRule.EXACT
				? items.findDuplicateId(key.userId(), key.languageCode(), key.normalizedLemma(), key.partOfSpeech())
					.stream()
					.toList()
				: items.findSameWordIds(key.userId(), key.languageCode(), key.normalizedLemma(), key.partOfSpeech());
		matches.stream().filter(existing -> !existing.equals(self)).findFirst().ifPresent(existing -> {
			throw new DuplicateVocabularyItemException(lemma, existing);
		});
	}

	/**
	 * Runs the write; if a concurrent request inserted the same word in between the check
	 * and the flush, the unique constraint fires and is reported like any duplicate. The
	 * lookup runs after the failed transaction has ended.
	 */
	private VocabularyItem saveRejectingDuplicates(Supplier<VocabularyItem> save,
			Supplier<DuplicateKey> key, Supplier<String> lemma) {
		try {
			return save.get();
		}
		catch (DataIntegrityViolationException ex) {
			DuplicateKey duplicateKey = key.get();
			if (duplicateKey == null || ex.getMessage() == null
					|| !ex.getMessage().contains(VocabularyItemRepository.LEMMA_UNIQUE_CONSTRAINT)) {
				throw ex;
			}
			UUID existing = items
				.findDuplicateId(duplicateKey.userId(), duplicateKey.languageCode(), duplicateKey.normalizedLemma(),
						duplicateKey.partOfSpeech())
				.orElseThrow(() -> ex);
			throw new DuplicateVocabularyItemException(lemma.get(), existing);
		}
	}

	private static VocabularyItem loaded(VocabularyItem item) {
		Hibernate.initialize(item.getExamples());
		Hibernate.initialize(item.getTags());
		return item;
	}

	private static String cleanLemma(String lemma) {
		String cleaned = lemma == null ? "" : lemma.strip();
		if (cleaned.isEmpty()) {
			throw new InvalidFieldException("lemma", "must not be blank");
		}
		if (cleaned.length() > MAX_LEMMA_LENGTH) {
			throw new InvalidFieldException("lemma", "must be at most " + MAX_LEMMA_LENGTH + " characters");
		}
		return cleaned;
	}

	private static String cleanExample(String sentence, int index) {
		String cleaned = sentence == null ? "" : sentence.strip();
		String field = "examples[" + index + "].sentence";
		if (cleaned.isEmpty()) {
			throw new InvalidFieldException(field, "must not be blank");
		}
		if (cleaned.length() > MAX_EXAMPLE_LENGTH) {
			throw new InvalidFieldException(field, "must be at most " + MAX_EXAMPLE_LENGTH + " characters");
		}
		return cleaned;
	}

	private static void checkStatus(KnowledgeStatus status) {
		if (status == KnowledgeStatus.MASTERED) {
			throw new InvalidFieldException("status", "can't be set to MASTERED; mastery is earned through practice");
		}
	}

	private static void checkGender(Gender gender, PartOfSpeech partOfSpeech) {
		if (gender != null && partOfSpeech != null && partOfSpeech != PartOfSpeech.NOUN) {
			throw new InvalidFieldException("gender", "only nouns have a gender");
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
