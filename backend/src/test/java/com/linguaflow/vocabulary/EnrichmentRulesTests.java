package com.linguaflow.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.linguaflow.ai.OutputProblems;
import com.linguaflow.common.domain.CefrLevel;

import com.linguaflow.vocabulary.EnrichmentAnswer.Batch;
import com.linguaflow.vocabulary.EnrichmentAnswer.Example;
import com.linguaflow.vocabulary.EnrichmentAnswer.VerbForms;
import com.linguaflow.vocabulary.EnrichmentAnswer.Word;
import com.linguaflow.vocabulary.EnrichmentRules.Input;
import org.junit.jupiter.api.Test;

class EnrichmentRulesTests {

	private static final List<Example> EXAMPLES = List.of(new Example("Der Hund bellt.", "The dog barks."),
			new Example("Ich habe einen Hund.", "I have a dog."), new Example("Der Hund schläft.", "The dog sleeps."));

	private static Word hund() {
		return new Word("Hund", PartOfSpeech.NOUN, Gender.MASCULINE, "Hunde", "dog", CefrLevel.A1, null, EXAMPLES,
				null);
	}

	private static Word laufen() {
		return new Word("laufen", PartOfSpeech.VERB, null, null, "to run; to walk", CefrLevel.A1,
				new VerbForms("lief", "gelaufen", Auxiliary.SEIN), EXAMPLES.subList(0, 2), null);
	}

	private static List<String> problems(Input input, Word answer) {
		OutputProblems problems = new OutputProblems();
		EnrichmentRules.check("", input, "de", answer, problems);
		return problems.list();
	}

	private static VocabularyItem item(String lemma) {
		return new VocabularyItem(UUID.randomUUID(), "de", lemma, LemmaNormalizer.normalize("de", lemma));
	}

	@Test
	void acceptsWellFormedAnswers() {
		assertThat(problems(new Input("Hund", null), hund())).isEmpty();
		assertThat(problems(new Input("laufen", PartOfSpeech.VERB), laufen())).isEmpty();
	}

	@Test
	void rejectsAnswersThatBreakGermanGrammarRules() {
		Word noGender = new Word("Hund", PartOfSpeech.NOUN, null, "die Hunde", "dog", null, null, EXAMPLES, null);
		assertThat(problems(new Input("Hund", null), noGender)).containsExactly(
				"gender: is required for a German noun",
				"plural: must be written without an article, e.g. \"Hunde\" not \"die Hunde\"");

		Word verbWithGender = new Word("laufen", PartOfSpeech.VERB, Gender.NEUTER, "x", "run", null, null, EXAMPLES,
				null);
		assertThat(problems(new Input("laufen", null), verbWithGender)).containsExactly(
				"gender: must be null for a VERB", "plural: must be null for a VERB",
				"verbForms: is required for a German verb");
	}

	@Test
	void keepsThePartOfSpeechTheLearnerChose() {
		assertThat(problems(new Input("Laufen", PartOfSpeech.NOUN), laufen()))
			.contains("partOfSpeech: the learner marked this word as NOUN, not VERB");
	}

	@Test
	void aBatchMustAnswerEveryWordInOrder() {
		List<Input> inputs = List.of(new Input("Hund", null), new Input("laufen", PartOfSpeech.VERB));
		OutputProblems swapped = new OutputProblems();
		EnrichmentRules.checkBatch(inputs, "de", new Batch(List.of(laufen(), hund())), swapped);
		assertThat(swapped.list()).contains("words[0].word: must be \"Hund\" (entry 1 answers word 1)",
				"words[1].word: must be \"laufen (verb)\" (entry 2 answers word 2)");

		OutputProblems missing = new OutputProblems();
		EnrichmentRules.checkBatch(inputs, "de", new Batch(List.of(hund())), missing);
		assertThat(missing.list()).containsExactly("words: must have exactly 2 entries, one per word, in order; got 1");

		OutputProblems fine = new OutputProblems();
		// The model may echo the word with or without the part-of-speech hint
		Word laufenWithHint = new Word("laufen (verb)", laufen().partOfSpeech(), null, null, laufen().translation(),
				null, laufen().verbForms(), laufen().examples(), null);
		EnrichmentRules.checkBatch(inputs, "de", new Batch(List.of(hund(), laufenWithHint)), fine);
		assertThat(fine.isEmpty()).isTrue();
	}

	@Test
	void fillsAnEmptyWordAndMarksWhatCameFromAi() {
		VocabularyItem item = item("der Hund");

		List<String> filled = EnrichmentRules.merge(item, hund(), pos -> false);

		assertThat(filled).containsExactly("partOfSpeech", "translation", "gender", "plural", "cefrLevel");
		assertThat(item.getPartOfSpeech()).isEqualTo(PartOfSpeech.NOUN);
		assertThat(item.getGender()).isEqualTo(Gender.MASCULINE);
		assertThat(item.getPlural()).isEqualTo("Hunde");
		assertThat(item.getTranslation()).isEqualTo("dog");
		assertThat(item.getExamples()).hasSize(3).allSatisfy(example -> assertThat(example.isAiGenerated()).isTrue());
		assertThat(item.getAiGeneratedFields()).containsExactlyElementsOf(filled);
		assertThat(item.getAiStatus()).isEqualTo(AiStatus.READY);
	}

	@Test
	void neverReplacesWhatTheLearnerGave() {
		VocabularyItem item = item("Bank");
		item.setPartOfSpeech(PartOfSpeech.NOUN);
		item.setTranslation("bench");
		item.setNotes("my note");
		item.addExample("Wir sitzen auf der Bank.", null, false);
		Word bank = new Word("Bank", PartOfSpeech.NOUN, Gender.FEMININE, "Banken", "bank; bench", CefrLevel.A2, null,
				EXAMPLES, "Two plurals: Bänke (benches), Banken (banks).");

		List<String> filled = EnrichmentRules.merge(item, bank, pos -> false);

		assertThat(filled).containsExactly("gender", "plural", "cefrLevel");
		assertThat(item.getTranslation()).isEqualTo("bench");
		assertThat(item.getNotes()).isEqualTo("my note");
		// The learner's example first, then AI ones up to three
		assertThat(item.getExamples()).extracting(VocabularyExample::isAiGenerated).containsExactly(false, true, true);
	}

	@Test
	void storesVerbFormsAndOnlyFitsFieldsToTheFinalPartOfSpeech() {
		VocabularyItem verb = item("laufen");
		EnrichmentRules.merge(verb, laufen(), pos -> false);
		assertThat(verb.getExtra()).isEqualTo(Map.of("praeteritum", "lief", "partizip2", "gelaufen", "auxiliary", "SEIN"));
		assertThat(verb.getAiGeneratedFields()).contains("verbForms");

		// The learner calls it a verb, but the answer describes a noun: no gender or plural
		VocabularyItem typed = item("Laufen");
		typed.setPartOfSpeech(PartOfSpeech.VERB);
		EnrichmentRules.merge(typed, hund(), pos -> false);
		assertThat(typed.getGender()).isNull();
		assertThat(typed.getPlural()).isNull();
	}

	@Test
	void leavesThePartOfSpeechUnsetWhenAnotherWordAlreadyHasIt() {
		VocabularyItem item = item("laufen");

		List<String> filled = EnrichmentRules.merge(item, laufen(), pos -> pos == PartOfSpeech.VERB);

		assertThat(item.getPartOfSpeech()).isNull();
		assertThat(filled).doesNotContain("partOfSpeech", "verbForms").contains("translation");
		assertThat(item.getAiStatus()).isEqualTo(AiStatus.READY);
	}

}
