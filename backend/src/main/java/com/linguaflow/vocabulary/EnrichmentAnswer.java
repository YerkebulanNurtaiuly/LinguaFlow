package com.linguaflow.vocabulary;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.linguaflow.common.domain.CefrLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * What the model returns when it enriches words (VOC-05). The JSON Schema sent with the
 * call is built from these records (AI-02); the descriptions tell the model what each
 * field means. Changing the shape needs a new prompt version, which also starts a fresh
 * AI cache.
 */
final class EnrichmentAnswer {

	private EnrichmentAnswer() {
	}

	@JsonClassDescription("One entry per word, in the order given")
	record Batch(@Size(min = 1, max = 50) List<@Valid Word> words) {
	}

	@JsonClassDescription("Dictionary data about one word or phrase")
	record Word(
			@JsonPropertyDescription("The word exactly as it was given") @NotBlank @Size(max = 250) String word,
			@JsonPropertyDescription("Part of speech of the word in its most common meaning, or the one the learner marked") PartOfSpeech partOfSpeech,
			@JsonPropertyDescription("Grammatical gender; nouns only, null otherwise") @Nullable Gender gender,
			@JsonPropertyDescription("Plural form without an article; nouns only, null for other words and nouns without a plural") @Nullable @Size(max = 200) String plural,
			@JsonPropertyDescription("Translation into the explanation language, most common meaning first, at most three meanings separated by \"; \"") @NotBlank @Size(max = 300) String translation,
			@JsonPropertyDescription("CEFR level at which learners usually meet the word") @Nullable CefrLevel cefrLevel,
			@JsonPropertyDescription("Principal parts; verbs only, null otherwise") @Nullable @Valid VerbForms verbForms,
			@JsonPropertyDescription("Two or three short example sentences using the word") @Size(min = 2, max = 3) List<@Valid Example> examples,
			@JsonPropertyDescription("Only what a learner genuinely needs, in the explanation language, e.g. an irregular form or a false friend; null when there is nothing") @Nullable @Size(max = 1000) String notes) {
	}

	record VerbForms(@JsonPropertyDescription("Third person singular Präteritum, e.g. \"lief\"") @NotBlank @Size(max = 100) String praeteritum,
			@JsonPropertyDescription("Partizip II, e.g. \"gelaufen\"") @NotBlank @Size(max = 100) String partizip2,
			@JsonPropertyDescription("Auxiliary of the perfect tense") Auxiliary auxiliary) {
	}

	enum Auxiliary {

		HABEN, SEIN

	}

	record Example(@JsonPropertyDescription("A short natural sentence in the learned language") @NotBlank @Size(max = 500) String sentence,
			@JsonPropertyDescription("Its translation into the explanation language") @NotBlank @Size(max = 500) String translation) {
	}

}
