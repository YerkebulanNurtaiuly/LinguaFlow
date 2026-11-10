package com.linguaflow.vocabulary;

import java.util.List;
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
	 * A partial update made by the learner: null means "leave unchanged". Every field the
	 * learner sets stops counting as AI-generated. A non-null {@code tagIds} replaces the word's
	 * tags (empty removes them all).
	 */
	public record VocabularyItemChanges(String lemma, PartOfSpeech partOfSpeech, String translation, Gender gender,
			String plural, CefrLevel cefrLevel, KnowledgeStatus status, String notes, List<UUID> tagIds) {

		public VocabularyItemChanges(String lemma, PartOfSpeech partOfSpeech, String translation, Gender gender,
				String plural, CefrLevel cefrLevel, KnowledgeStatus status, String notes) {
			this(lemma, partOfSpeech, translation, gender, plural, cefrLevel, status, notes, null);
		}

	}

}
