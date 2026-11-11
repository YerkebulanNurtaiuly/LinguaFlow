package com.linguaflow.vocabulary;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.vocabulary.VocabularyCommands.ClearableField;
import com.linguaflow.vocabulary.VocabularyCommands.ExampleChange;
import com.linguaflow.vocabulary.VocabularyCommands.NewExample;
import com.linguaflow.vocabulary.VocabularyCommands.NewVocabularyItem;
import com.linguaflow.vocabulary.VocabularyCommands.VerbFormsChange;
import com.linguaflow.vocabulary.VocabularyCommands.VocabularyItemChanges;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the vocabulary API (VOC-02). */
final class VocabularyDtos {

	private VocabularyDtos() {
	}

	@Schema(name = "CreateVocabularyRequest",
			description = "Only lemma is required; missing details are filled in by AI enrichment later")
	record CreateRequest(
			@Schema(example = "der Hund", description = "The word or phrase as you want to see it, article included")
			@NotBlank @Size(max = 200) String lemma,
			PartOfSpeech partOfSpeech, @Schema(example = "dog") @Size(max = 500) String translation, Gender gender,
			@Schema(example = "die Hunde") @Size(max = 200) String plural, CefrLevel cefrLevel,
			@Schema(description = "Defaults to LEARNING; MASTERED is earned, not set") KnowledgeStatus status,
			@Size(max = 2000) String notes, @Size(max = 10) List<@Valid @NotNull ExampleRequest> examples,
			@Schema(description = "IDs of your tags in the same language") @Size(max = TagService.MAX_TAGS_PER_WORD)
			List<@NotNull UUID> tagIds) {

		NewVocabularyItem toCommand() {
			List<NewExample> newExamples = examples == null ? List.of()
					: examples.stream().map(e -> new NewExample(e.sentence(), e.translation())).toList();
			return new NewVocabularyItem(lemma, partOfSpeech, translation, gender, plural, cefrLevel, status, notes,
					newExamples, tagIds);
		}

	}

	@Schema(name = "VocabularyExampleRequest")
	record ExampleRequest(@Schema(example = "Der Hund bellt.") @NotBlank @Size(max = 1000) String sentence,
			@Schema(example = "The dog barks.") @Size(max = 1000) String translation) {
	}

	@Schema(name = "UpdateVocabularyRequest",
			description = "Partial update: absent or null fields stay unchanged; an empty string clears a text field")
	record UpdateRequest(
			@Schema(example = "der Hund") @Size(min = 1, max = 200)
			@Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") String lemma,
			PartOfSpeech partOfSpeech, @Size(max = 500) String translation, Gender gender,
			@Size(max = 200) String plural, CefrLevel cefrLevel,
			@Schema(description = "MASTERED can't be set; it is earned through practice") KnowledgeStatus status,
			@Size(max = 2000) String notes,
			@Schema(description = "Replaces the word's tags; an empty list removes them all")
			@Size(max = TagService.MAX_TAGS_PER_WORD) List<@NotNull UUID> tagIds,
			@Schema(description = "Replaces all examples, in this order; an empty list removes them. Send an "
					+ "example's id back unchanged to keep it marked as AI-generated")
			@Size(max = VocabularyService.MAX_EXAMPLES) List<@Valid @NotNull ExampleUpdate> examples,
			@Schema(description = "Sets a verb's principal parts; only for verbs") @Valid VerbFormsRequest verbForms,
			@Schema(description = "Fields to clear, which an empty string can't express")
			Set<@NotNull ClearableField> clear) {

		VocabularyItemChanges toCommand() {
			return new VocabularyItemChanges(lemma, partOfSpeech, translation, gender, plural, cefrLevel, status,
					notes, tagIds,
					examples == null ? null
							: examples.stream().map(e -> new ExampleChange(e.id(), e.sentence(), e.translation())).toList(),
					verbForms == null ? null
							: new VerbFormsChange(verbForms.praeteritum(), verbForms.partizip2(), verbForms.auxiliary()),
					clear);
		}

	}

	@Schema(name = "VocabularyItem")
	record ItemResponse(UUID id, @Schema(example = "der Hund") String lemma,
			@Schema(example = "der", description = "Definite article, derived from gender for German nouns") String article,
			PartOfSpeech partOfSpeech, @Schema(example = "dog") String translation, Gender gender,
			@Schema(example = "die Hunde") String plural,
			@Schema(description = "Word-type data, e.g. verbs: praeteritum, partizip2, auxiliary")
			Map<String, Object> extra,
			CefrLevel cefrLevel, KnowledgeStatus status, AiStatus aiStatus,
			@Schema(description = "Fields whose current value came from AI") List<String> aiGeneratedFields,
			String notes, List<ExampleResponse> examples, List<TagResponse> tags, Instant createdAt,
			Instant updatedAt, @Schema(description = "Increases with every change") long version) {

		static ItemResponse from(VocabularyItem item) {
			return new ItemResponse(item.getId(), item.getLemma(), item.article(), item.getPartOfSpeech(),
					item.getTranslation(), item.getGender(), item.getPlural(), Map.copyOf(item.getExtra()),
					item.getCefrLevel(), item.getStatus(), item.getAiStatus(), List.copyOf(item.getAiGeneratedFields()),
					item.getNotes(), item.getExamples().stream().map(ExampleResponse::from).toList(),
					item.getTags()
						.stream()
						.sorted(Comparator.comparing(Tag::getName, String.CASE_INSENSITIVE_ORDER))
						.map(TagResponse::from)
						.toList(),
					item.getCreatedAt(), item.getUpdatedAt(), item.getVersion());
		}

	}

	@Schema(name = "VocabularyExampleUpdate")
	record ExampleUpdate(@Schema(description = "The example's id, when it's an existing one") UUID id,
			@Schema(example = "Der Hund bellt.") @NotBlank @Size(max = 1000) String sentence,
			@Schema(example = "The dog barks.") @Size(max = 1000) String translation) {
	}

	@Schema(name = "VerbForms", description = "A German verb's principal parts")
	record VerbFormsRequest(@Schema(example = "lief") @NotBlank @Size(max = 100) String praeteritum,
			@Schema(example = "gelaufen") @NotBlank @Size(max = 100) String partizip2, @NotNull Auxiliary auxiliary) {
	}

	@Schema(name = "VocabularyRegenerateResult", description = "The word, back to PENDING_AI, and the AI job filling it in")
	record RegenerateResponse(ItemResponse item, UUID aiJobId) {
	}

	@Schema(name = "VocabularyExample")
	record ExampleResponse(UUID id, String sentence, String translation, boolean aiGenerated) {

		static ExampleResponse from(VocabularyExample example) {
			return new ExampleResponse(example.getId(), example.getSentence(), example.getTranslation(),
					example.isAiGenerated());
		}

	}

	@Schema(name = "VocabularyTag")
	record TagResponse(UUID id, String name) {

		static TagResponse from(Tag tag) {
			return new TagResponse(tag.getId(), tag.getName());
		}

	}

}
