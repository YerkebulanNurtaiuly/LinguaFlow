package com.linguaflow.vocabulary;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.KnowledgeStatus;

/** Inputs of {@link VocabularyService}; the API layer (VOC-02) maps its requests onto these. */
public final class VocabularyCommands {

	private VocabularyCommands() {
	}

	/**
	 * A new word. Only {@code lemma} is required; everything else may be left null for AI
	 * enrichment to fill in (VOC-05). {@code status} defaults to LEARNING. {@code tagIds} are tags (VOC-03) of the same language.
	 */
	public record NewVocabularyItem(String lemma, PartOfSpeech partOfSpeech, String translation, Gender gender,
			String plural, CefrLevel cefrLevel, KnowledgeStatus status, String notes, List<NewExample> examples,
			List<UUID> tagIds) {

		public NewVocabularyItem {
			examples = examples == null ? List.of() : List.copyOf(examples);
			tagIds = tagIds == null ? List.of() : List.copyOf(tagIds);
		}

		public NewVocabularyItem(String lemma, PartOfSpeech partOfSpeech, String translation, Gender gender,
				String plural, CefrLevel cefrLevel, KnowledgeStatus status, String notes, List<NewExample> examples) {
			this(lemma, partOfSpeech, translation, gender, plural, cefrLevel, status, notes, examples, List.of());
		}

		public static NewVocabularyItem of(String lemma) {
			return new NewVocabularyItem(lemma, null, null, null, null, null, null, null, List.of());
		}

	}

	public record NewExample(String sentence, String translation) {
	}

	/**
	 * One example in a PATCH that replaces all of a word's examples (VOC-06). An example sent
	 * back unchanged with its {@code id} keeps its AI flag; anything else counts as the learner's.
	 */
	public record ExampleChange(UUID id, String sentence, String translation) {
	}

	/** A verb's principal parts, set by the learner (VOC-06). */
	public record VerbFormsChange(String praeteritum, String partizip2, Auxiliary auxiliary) {
	}

	/** Fields a PATCH can clear, which an empty string can't express (VOC-06). */
	public enum ClearableField {

		PART_OF_SPEECH, GENDER, CEFR_LEVEL, VERB_FORMS

	}

	/**
	 * A partial update made by the learner: null means "leave unchanged". Every field the
	 * learner sets stops counting as AI-generated. A non-null {@code tagIds} replaces the word's
	 * tags (empty removes them all).
	 */
	public record VocabularyItemChanges(String lemma, PartOfSpeech partOfSpeech, String translation, Gender gender,
			String plural, CefrLevel cefrLevel, KnowledgeStatus status, String notes, List<UUID> tagIds,
			List<ExampleChange> examples, VerbFormsChange verbForms, Set<ClearableField> clear) {

		public VocabularyItemChanges {
			examples = examples == null ? null : List.copyOf(examples);
			clear = clear == null ? Set.of() : Set.copyOf(clear);
		}

		public VocabularyItemChanges(String lemma, PartOfSpeech partOfSpeech, String translation, Gender gender,
				String plural, CefrLevel cefrLevel, KnowledgeStatus status, String notes, List<UUID> tagIds) {
			this(lemma, partOfSpeech, translation, gender, plural, cefrLevel, status, notes, tagIds, null, null, null);
		}

		public VocabularyItemChanges(String lemma, PartOfSpeech partOfSpeech, String translation, Gender gender,
				String plural, CefrLevel cefrLevel, KnowledgeStatus status, String notes) {
			this(lemma, partOfSpeech, translation, gender, plural, cefrLevel, status, notes, null);
		}

	}

}
