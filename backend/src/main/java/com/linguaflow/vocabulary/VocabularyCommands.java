package com.linguaflow.vocabulary;

import java.util.List;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.KnowledgeStatus;

/** Inputs of {@link VocabularyService}; the API layer (VOC-02) maps its requests onto these. */
public final class VocabularyCommands {

	private VocabularyCommands() {
	}

	/**
	 * A new word. Only {@code lemma} is required; everything else may be left null for AI
	 * enrichment to fill in (VOC-05). {@code status} defaults to LEARNING.
	 */
	public record NewVocabularyItem(String lemma, PartOfSpeech partOfSpeech, String translation, Gender gender,
			String plural, CefrLevel cefrLevel, KnowledgeStatus status, String notes, List<NewExample> examples) {

		public NewVocabularyItem {
			examples = examples == null ? List.of() : List.copyOf(examples);
		}

		public static NewVocabularyItem of(String lemma) {
			return new NewVocabularyItem(lemma, null, null, null, null, null, null, null, List.of());
		}

	}

	public record NewExample(String sentence, String translation) {
	}

	/**
	 * A partial update made by the learner: null means "leave unchanged". Every field the
	 * learner sets stops counting as AI-generated.
	 */
	public record VocabularyItemChanges(String lemma, PartOfSpeech partOfSpeech, String translation, Gender gender,
			String plural, CefrLevel cefrLevel, KnowledgeStatus status, String notes) {
	}

}
