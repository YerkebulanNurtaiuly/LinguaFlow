package com.linguaflow.vocabulary;

import java.util.List;
import java.util.Locale;

import com.linguaflow.ai.AiCaller;
import com.linguaflow.ai.prompt.PromptTemplate;
import com.linguaflow.ai.prompt.PromptVar;
import com.linguaflow.ai.prompt.PromptVariables;
import com.linguaflow.ai.prompt.RenderedPrompt;
import com.linguaflow.testsupport.EvalReport;
import com.linguaflow.testsupport.LiveAi;
import com.linguaflow.vocabulary.EnrichmentAnswer.Batch;
import com.linguaflow.vocabulary.EnrichmentAnswer.Word;
import com.linguaflow.vocabulary.EnrichmentRules.Input;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Live eval of vocabulary enrichment (QA-03): one real batch call with the production
 * prompt, settings and checks, scored against known dictionary facts. Off by default
 * because it costs money; the nightly {@code ai-eval} workflow runs it with
 * {@code -Dai.live=true} and {@code AI_API_KEY}.
 */
@EnabledIfSystemProperty(named = "ai.live", matches = "true")
class EnrichmentEvalTests {

	/** A word and what any good dictionary says about it. */
	private record Expected(String lemma, @Nullable PartOfSpeech marked, PartOfSpeech partOfSpeech,
			@Nullable Gender gender, @Nullable String plural, String translation, @Nullable String partizip2,
			@Nullable Auxiliary auxiliary) {

		Input input() {
			return new Input(lemma, marked);
		}

	}

	private static final List<Expected> WORDS = List.of(
			new Expected("Hund", null, PartOfSpeech.NOUN, Gender.MASCULINE, "Hunde", "dog", null, null),
			new Expected("Katze", null, PartOfSpeech.NOUN, Gender.FEMININE, "Katzen", "cat", null, null),
			new Expected("Mädchen", null, PartOfSpeech.NOUN, Gender.NEUTER, "Mädchen", "girl", null, null),
			new Expected("Haus", null, PartOfSpeech.NOUN, Gender.NEUTER, "Häuser", "house", null, null),
			new Expected("laufen", PartOfSpeech.VERB, PartOfSpeech.VERB, null, null, "run", "gelaufen",
					Auxiliary.SEIN),
			new Expected("helfen", null, PartOfSpeech.VERB, null, null, "help", "geholfen", Auxiliary.HABEN),
			new Expected("schön", null, PartOfSpeech.ADJECTIVE, null, null, "beautiful", null, null));

	@Test
	void enrichesKnownWordsCorrectly() {
		EvalReport report = new EvalReport("vocabulary-enrichment", 0.85);
		List<Input> inputs = WORDS.stream().map(Expected::input).toList();
		try (LiveAi ai = LiveAi.start()) {
			RenderedPrompt prompt = ai.prompts()
				.render(PromptTemplate.VOCABULARY_ENRICHMENT_BATCH, PromptVariables.forLanguages("de", "en")
					.set(PromptVar.WORDS, inputs.stream().map(Input::line).toList()));
			Batch batch = ai.llm()
				.complete(AiCaller.system(), prompt, Batch.class,
						(value, problems) -> EnrichmentRules.checkBatch(inputs, "de", value, problems))
				.value();
			for (int i = 0; i < WORDS.size(); i++) {
				score(report, WORDS.get(i), batch.words().get(i));
			}
		}
		catch (RuntimeException ex) {
			report.fail("batch call", ex.getClass().getSimpleName() + ": " + ex.getMessage());
		}
		report.finish();
	}

	private static void score(EvalReport report, Expected expected, Word word) {
		String name = expected.lemma();
		report.check(name + ": part of speech", String.valueOf(word.partOfSpeech()),
				word.partOfSpeech() == expected.partOfSpeech());
		report.check(name + ": translation", word.translation(),
				word.translation().toLowerCase(Locale.ROOT).contains(expected.translation()));
		if (expected.gender() != null) {
			report.check(name + ": gender", String.valueOf(word.gender()), word.gender() == expected.gender());
			report.check(name + ": plural", word.plural(), expected.plural().equals(word.plural()));
		}
		if (expected.partizip2() != null) {
			boolean forms = word.verbForms() != null && expected.partizip2().equals(word.verbForms().partizip2())
					&& word.verbForms().auxiliary() == expected.auxiliary();
			report.check(name + ": verb forms", String.valueOf(word.verbForms()), forms);
		}
	}

}
