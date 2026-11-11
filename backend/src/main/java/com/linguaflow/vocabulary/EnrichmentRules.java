package com.linguaflow.vocabulary;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import com.linguaflow.ai.OutputProblems;
import com.linguaflow.vocabulary.EnrichmentAnswer.Word;
import com.linguaflow.vocabulary.VocabularyService.AiFields;
import org.jspecify.annotations.Nullable;

/**
 * The deterministic rules around AI enrichment (VOC-05): what makes an answer acceptable,
 * and how it's merged into a word. The model only suggests; these decide.
 */
final class EnrichmentRules {

	/** A word gets at most this many examples, its own first. */
	static final int MAX_EXAMPLES = 3;

	/** Keys in {@code vocabulary_items.extra} for a verb's principal parts. */
	static final String PRAETERITUM = "praeteritum";

	static final String PARTIZIP_2 = "partizip2";

	static final String AUXILIARY = "auxiliary";

	private static final List<String> GERMAN_ARTICLES = List.of("der ", "die ", "das ");

	private EnrichmentRules() {
	}

	/** One word sent to the model: the learner's spelling and, if chosen, its part of speech. */
	record Input(String lemma, @Nullable PartOfSpeech partOfSpeech) {

		/** How the word is written in the prompt, e.g. {@code laufen (verb)}. */
		String line() {
			return partOfSpeech == null ? lemma : lemma + " (" + partOfSpeech.name().toLowerCase(Locale.ROOT) + ")";
		}

	}

	/** Domain checks on one answer (AI-02), reported under {@code path}. */
	static void check(String path, Input input, String languageCode, Word answer, OutputProblems problems) {
		String at = path.isEmpty() ? "" : path + ".";
		if (input.partOfSpeech() != null && answer.partOfSpeech() != input.partOfSpeech()) {
			problems.add(at + "partOfSpeech", "the learner marked this word as " + input.partOfSpeech() + ", not "
					+ answer.partOfSpeech());
		}
		boolean noun = answer.partOfSpeech() == PartOfSpeech.NOUN;
		if (!noun && answer.gender() != null) {
			problems.add(at + "gender", "must be null for a " + answer.partOfSpeech());
		}
		if (!noun && answer.plural() != null) {
			problems.add(at + "plural", "must be null for a " + answer.partOfSpeech());
		}
		if (answer.partOfSpeech() != PartOfSpeech.VERB && answer.verbForms() != null) {
			problems.add(at + "verbForms", "must be null for a " + answer.partOfSpeech());
		}
		if ("de".equals(languageCode)) {
			if (noun && answer.gender() == null) {
				problems.add(at + "gender", "is required for a German noun");
			}
			if (answer.plural() != null && GERMAN_ARTICLES.stream()
				.anyMatch(article -> answer.plural().toLowerCase(Locale.ROOT).startsWith(article))) {
				problems.add(at + "plural", "must be written without an article, e.g. \"Hunde\" not \"die Hunde\"");
			}
			if (answer.partOfSpeech() == PartOfSpeech.VERB && answer.verbForms() == null) {
				problems.add(at + "verbForms", "is required for a German verb");
			}
		}
	}

	/** The batch's entries must match the words sent, in order. */
	static void checkBatch(List<Input> inputs, String languageCode, EnrichmentAnswer.Batch batch,
			OutputProblems problems) {
		if (batch.words().size() != inputs.size()) {
			problems.add("words", "must have exactly " + inputs.size() + " entries, one per word, in order; got "
					+ batch.words().size());
			return;
		}
		for (int i = 0; i < inputs.size(); i++) {
			Input input = inputs.get(i);
			Word answer = batch.words().get(i);
			if (!sameWord(input, answer.word())) {
				problems.add("words[" + i + "].word", "must be \"" + input.line() + "\" (entry " + (i + 1)
						+ " answers word " + (i + 1) + ")");
			}
			check("words[" + i + "]", input, languageCode, answer, problems);
		}
	}

	private static boolean sameWord(Input input, String echoed) {
		String echo = echoed.strip();
		return echo.equalsIgnoreCase(input.line()) || echo.equalsIgnoreCase(input.lemma());
	}

	/**
	 * Fills in what the learner left empty and marks it as AI-generated. A value the
	 * learner gave, or typed in while the AI was working, is never replaced.
	 * @param partOfSpeechTaken whether another of the user's words already has this lemma
	 * with that part of speech; then the part of speech stays unset (the unique key)
	 * @return the fields that were filled
	 */
	static List<String> merge(VocabularyItem item, Word answer, Predicate<PartOfSpeech> partOfSpeechTaken) {
		Set<String> filled = new LinkedHashSet<>();
		if (item.getPartOfSpeech() == null && !partOfSpeechTaken.test(answer.partOfSpeech())) {
			item.setPartOfSpeech(answer.partOfSpeech());
			filled.add(AiFields.PART_OF_SPEECH);
		}
		PartOfSpeech partOfSpeech = item.getPartOfSpeech();
		if (item.getTranslation() == null) {
			item.setTranslation(answer.translation().strip());
			filled.add(AiFields.TRANSLATION);
		}
		// Gender, plural and verb forms only where they fit the word's final part of speech
		if (partOfSpeech == PartOfSpeech.NOUN && answer.partOfSpeech() == PartOfSpeech.NOUN) {
			if (item.getGender() == null && answer.gender() != null) {
				item.setGender(answer.gender());
				filled.add(AiFields.GENDER);
			}
			if (item.getPlural() == null && answer.plural() != null && !answer.plural().isBlank()) {
				item.setPlural(answer.plural().strip());
				filled.add(AiFields.PLURAL);
			}
		}
		if (partOfSpeech == PartOfSpeech.VERB && answer.verbForms() != null && !item.hasVerbForms()) {
			item.setVerbForms(answer.verbForms().praeteritum().strip(), answer.verbForms().partizip2().strip(),
					answer.verbForms().auxiliary());
			filled.add(AiFields.VERB_FORMS);
		}
		if (item.getCefrLevel() == null && answer.cefrLevel() != null) {
			item.setCefrLevel(answer.cefrLevel());
			filled.add(AiFields.CEFR_LEVEL);
		}
		if (item.getNotes() == null && answer.notes() != null && !answer.notes().isBlank()) {
			item.setNotes(answer.notes().strip());
			filled.add(AiFields.NOTES);
		}
		for (EnrichmentAnswer.Example example : answer.examples()) {
			if (item.getExamples().size() >= MAX_EXAMPLES) {
				break;
			}
			item.addExample(example.sentence().strip(), example.translation().strip(), true);
		}
		List<String> aiFields = new ArrayList<>(item.getAiGeneratedFields());
		filled.stream().filter(field -> !aiFields.contains(field)).forEach(aiFields::add);
		item.setAiGeneratedFields(aiFields);
		item.setAiStatus(AiStatus.READY);
		return List.copyOf(filled);
	}

}
