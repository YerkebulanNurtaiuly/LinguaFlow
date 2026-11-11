package com.linguaflow.vocabulary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.common.error.ConflictException;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.common.error.NotFoundException;
import com.linguaflow.user.ActiveLanguageService;
import com.linguaflow.vocabulary.VocabularyCommands.ClearableField;
import com.linguaflow.vocabulary.VocabularyCommands.ExampleChange;
import com.linguaflow.vocabulary.VocabularyCommands.NewExample;
import com.linguaflow.vocabulary.VocabularyCommands.NewVocabularyItem;
import com.linguaflow.vocabulary.VocabularyCommands.VerbFormsChange;
import com.linguaflow.vocabulary.VocabularyCommands.VocabularyItemChanges;
import org.hibernate.Hibernate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

	private final ApplicationEventPublisher events;

	private final EnrichmentQueue enrichment;

	VocabularyService(VocabularyItemRepository items, ActiveLanguageService activeLanguages, TagService tags,
			PlatformTransactionManager transactionManager, ApplicationEventPublisher events,
			EnrichmentQueue enrichment) {
		this.items = items;
		this.enrichment = enrichment;
		this.events = events;
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
		return create(userId, request, rule, true);
	}

	/**
	 * @param enrichNow queue the word for AI enrichment (VOC-05) in the same transaction;
	 * false when the caller adds many words and queues them in batches itself
	 */
	VocabularyItem create(UUID userId, NewVocabularyItem request, DuplicateRule rule, boolean enrichNow) {
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
		VocabularyItem created = saveRejectingDuplicates(() -> transactions.execute(tx -> {
			item.replaceTags(tags.resolveForWord(userId, language, request.tagIds()));
			VocabularyItem saved = items.saveAndFlush(item);
			if (enrichNow) {
				// In the same transaction: no word without its job, no job without its word
				enrichment.enqueue(userId, List.of(saved.getId()));
			}
			return saved;
		}), () -> key, () -> lemma);
		// After the commit, so listeners see the word (e.g. the active study session, SES-01)
		events.publishEvent(new VocabularyItemCreated(userId, language, created.getId()));
		return created;
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
			Set<ClearableField> clear = changes.clear();
			rejectSetAndClear(clear, ClearableField.PART_OF_SPEECH, changes.partOfSpeech(), "partOfSpeech");
			rejectSetAndClear(clear, ClearableField.GENDER, changes.gender(), "gender");
			rejectSetAndClear(clear, ClearableField.CEFR_LEVEL, changes.cefrLevel(), "cefrLevel");
			rejectSetAndClear(clear, ClearableField.VERB_FORMS, changes.verbForms(), "verbForms");
			boolean clearPartOfSpeech = clear.contains(ClearableField.PART_OF_SPEECH);
			PartOfSpeech partOfSpeech = clearPartOfSpeech ? null
					: changes.partOfSpeech() == null ? item.getPartOfSpeech() : changes.partOfSpeech();
			String normalized = LemmaNormalizer.normalize(item.getLanguageCode(), lemma);
			lemmaHolder[0] = lemma;
			key[0] = new DuplicateKey(userId, item.getLanguageCode(), normalized, partOfSpeech);
			checkGender(changes.gender(), partOfSpeech);
			if (changes.status() != null) {
				checkStatus(changes.status());
			}
			if (changes.verbForms() != null && partOfSpeech != PartOfSpeech.VERB) {
				throw new InvalidFieldException("verbForms", "only verbs have verb forms");
			}
			if (changes.lemma() != null || changes.partOfSpeech() != null || clearPartOfSpeech) {
				rejectDuplicate(key[0], lemma, item.getId(), DuplicateRule.SAME_WORD);
			}

			if (changes.lemma() != null) {
				item.rename(lemma, normalized);
			}
			edit(item, AiFields.PART_OF_SPEECH, changes.partOfSpeech(), item::setPartOfSpeech);
			if (clearPartOfSpeech) {
				item.setPartOfSpeech(null);
				item.markEditedByUser(AiFields.PART_OF_SPEECH);
			}
			edit(item, AiFields.TRANSLATION, changes.translation(), value -> item.setTranslation(blankToNull(value)));
			edit(item, AiFields.GENDER, changes.gender(), item::setGender);
			if (clear.contains(ClearableField.GENDER)
					|| partOfSpeech != null && partOfSpeech != PartOfSpeech.NOUN && item.getGender() != null) {
				// Cleared, or no longer a noun so the gender no longer applies
				item.setGender(null);
				item.markEditedByUser(AiFields.GENDER);
			}
			edit(item, AiFields.PLURAL, changes.plural(), value -> item.setPlural(blankToNull(value)));
			edit(item, AiFields.CEFR_LEVEL, changes.cefrLevel(), item::setCefrLevel);
			if (clear.contains(ClearableField.CEFR_LEVEL)) {
				item.setCefrLevel(null);
				item.markEditedByUser(AiFields.CEFR_LEVEL);
			}
			if (changes.verbForms() != null) {
				VerbFormsChange forms = changes.verbForms();
				item.setVerbForms(requiredForm(forms.praeteritum(), "verbForms.praeteritum"),
						requiredForm(forms.partizip2(), "verbForms.partizip2"), requireAuxiliary(forms.auxiliary()));
				item.markEditedByUser(AiFields.VERB_FORMS);
			}
			if (clear.contains(ClearableField.VERB_FORMS)
					|| partOfSpeech != null && partOfSpeech != PartOfSpeech.VERB && item.hasVerbForms()) {
				// Cleared, or no longer a verb
				item.removeVerbForms();
				item.markEditedByUser(AiFields.VERB_FORMS);
			}
			if (changes.examples() != null) {
				replaceExamples(item, changes.examples());
			}
			if (changes.status() != null) {
				item.changeStatusByUser(changes.status());
			}
			if (changes.notes() != null) {
				item.setNotes(blankToNull(changes.notes()));
				item.markEditedByUser(AiFields.NOTES);
			}
			if (changes.tagIds() != null) {
				item.replaceTags(tags.resolveForWord(userId, item.getLanguageCode(), changes.tagIds()));
			}
			return loaded(items.saveAndFlush(item));
		}), () -> key[0], () -> lemmaHolder[0]);
	}

	/**
	 * The user's words among {@code ids}, with examples and tags loaded, by ID; for other
	 * modules that show words, e.g. flashcard content (FC-02). Others' words are left out.
	 */
	@Transactional(readOnly = true)
	public Map<UUID, VocabularyItem> findOwned(UUID userId, Collection<UUID> ids) {
		Map<UUID, VocabularyItem> byId = new HashMap<>();
		if (!ids.isEmpty()) {
			items.findOwnedByIds(userId, ids).forEach(item -> byId.put(item.getId(), loaded(item)));
		}
		return byId;
	}

	/**
	 * Up to {@code limit} of the user's words in a language, most recently changed first;
	 * for other modules' AI prompts, e.g. examples in a grammar explanation (GRM-04).
	 */
	@Transactional(readOnly = true)
	public List<String> recentLemmas(UUID userId, String languageCode, int limit) {
		return items.findRecentLemmas(userId, languageCode, PageRequest.of(0, limit));
	}

	/** A word sent back to the AI, and the job doing it (VOC-06). */
	public record Regenerated(VocabularyItem item, UUID aiJobId) {
	}

	/**
	 * Asks the AI again (VOC-06): every value the AI filled in is cleared, AI examples are
	 * removed, and a fresh enrichment job is queued. The learner's own values and examples
	 * stay, and so does the part of speech, which tells the AI which word is meant; change
	 * it first if it's wrong. The job skips the shared cache, so the answer is new.
	 * @throws ConflictException while the AI is still working on the word
	 */
	public Regenerated regenerate(UUID userId, UUID id) {
		return transactions.execute(tx -> {
			VocabularyItem item = items.requireOwned(id, userId);
			if (item.getAiStatus() == AiStatus.PENDING_AI) {
				throw new ConflictException("AI is already filling in this word. Wait for it to finish, then try again.");
			}
			List<String> aiFields = item.getAiGeneratedFields();
			if (aiFields.contains(AiFields.TRANSLATION)) {
				item.setTranslation(null);
			}
			if (aiFields.contains(AiFields.GENDER)) {
				item.setGender(null);
			}
			if (aiFields.contains(AiFields.PLURAL)) {
				item.setPlural(null);
			}
			if (aiFields.contains(AiFields.CEFR_LEVEL)) {
				item.setCefrLevel(null);
			}
			if (aiFields.contains(AiFields.NOTES)) {
				item.setNotes(null);
			}
			if (aiFields.contains(AiFields.VERB_FORMS)) {
				item.removeVerbForms();
			}
			item.setAiGeneratedFields(
					aiFields.contains(AiFields.PART_OF_SPEECH) ? List.of(AiFields.PART_OF_SPEECH) : List.of());
			rewriteExamples(item, item.getExamples()
				.stream()
				.filter(example -> !example.isAiGenerated())
				.map(example -> new ExampleData(example.getSentence(), example.getTranslation(), false))
				.toList());
			item.setAiStatus(AiStatus.PENDING_AI);
			VocabularyItem saved = items.saveAndFlush(item);
			UUID jobId = enrichment.enqueueFresh(userId, saved.getId());
			return new Regenerated(loaded(saved), jobId);
		});
	}

	/** At most this many examples per word. */
	public static final int MAX_EXAMPLES = 10;

	private record ExampleData(String sentence, String translation, boolean aiGenerated) {
	}

	/** A PATCH's examples replace the word's; one sent back unchanged keeps its AI flag. */
	private void replaceExamples(VocabularyItem item, List<ExampleChange> wanted) {
		if (wanted.size() > MAX_EXAMPLES) {
			throw new InvalidFieldException("examples", "must have at most " + MAX_EXAMPLES + " examples");
		}
		Map<UUID, VocabularyExample> existing = new HashMap<>();
		item.getExamples().forEach(example -> existing.put(example.getId(), example));
		List<ExampleData> rewritten = new ArrayList<>();
		for (int i = 0; i < wanted.size(); i++) {
			ExampleChange change = wanted.get(i);
			if (change == null) {
				throw new InvalidFieldException("examples[" + i + "]", "must not be null");
			}
			String sentence = cleanExample(change.sentence(), i);
			String translation = blankToNull(change.translation());
			if (translation != null && translation.length() > MAX_EXAMPLE_LENGTH) {
				throw new InvalidFieldException("examples[" + i + "].translation",
						"must be at most " + MAX_EXAMPLE_LENGTH + " characters");
			}
			VocabularyExample before = change.id() == null ? null : existing.get(change.id());
			boolean unchangedAi = before != null && before.isAiGenerated() && before.getSentence().equals(sentence)
					&& Objects.equals(before.getTranslation(), translation);
			rewritten.add(new ExampleData(sentence, translation, unchangedAi));
		}
		rewriteExamples(item, rewritten);
	}

	/**
	 * Replaces the examples. Positions are unique per word and Hibernate inserts before it
	 * deletes, so the old rows are flushed away first.
	 */
	private void rewriteExamples(VocabularyItem item, List<ExampleData> examples) {
		item.clearExamples();
		items.saveAndFlush(item);
		examples.forEach(example -> item.addExample(example.sentence(), example.translation(), example.aiGenerated()));
	}

	private static void rejectSetAndClear(Set<ClearableField> clear, ClearableField field, Object value,
			String name) {
		if (value != null && clear.contains(field)) {
			throw new InvalidFieldException(name, "can't be set and cleared at once");
		}
	}

	private static String requiredForm(String form, String field) {
		String value = form == null ? "" : form.strip();
		if (value.isEmpty() || value.length() > 100) {
			throw new InvalidFieldException(field, "must be 1 to 100 characters");
		}
		return value;
	}

	private static Auxiliary requireAuxiliary(Auxiliary auxiliary) {
		if (auxiliary == null) {
			throw new InvalidFieldException("verbForms.auxiliary", "must be HABEN or SEIN");
		}
		return auxiliary;
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

		/** The principal parts of a verb, stored in {@code extra} (VOC-05). */
		public static final String VERB_FORMS = "verbForms";

		public static final String NOTES = "notes";

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
