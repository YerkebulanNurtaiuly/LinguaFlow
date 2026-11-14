package com.linguaflow.vocabulary;

import java.util.List;

import com.linguaflow.ai.AiCaller;
import com.linguaflow.ai.AiUseCase;
import com.linguaflow.ai.InvalidLlmOutputException;
import com.linguaflow.ai.LlmPrompt;
import com.linguaflow.ai.StructuredResponse;
import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.testsupport.SampleAnswers;
import com.linguaflow.vocabulary.EnrichmentAnswer.Batch;
import com.linguaflow.vocabulary.EnrichmentAnswer.Word;
import com.linguaflow.vocabulary.EnrichmentRules.Input;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Contract of vocabulary enrichment (QA-03): sample model answers go through the
 * production records and {@link EnrichmentRules}, exactly as
 * {@link VocabularyEnrichmentHandler} calls them.
 */
class EnrichmentContractTests {

	private static final LlmPrompt PROMPT = new LlmPrompt("system", "user");

	private static final List<Input> WORDS = List.of(new Input("Hund", null), new Input("laufen", PartOfSpeech.VERB),
			new Input("schön", null));

	private static StructuredResponse<Batch> batch(String sample) {
		return SampleAnswers.llmAnswering(SampleAnswers.read("vocabulary-enrichment-batch/" + sample))
			.complete(AiCaller.system(), AiUseCase.VOCABULARY_ENRICHMENT, PROMPT, Batch.class,
					(value, problems) -> EnrichmentRules.checkBatch(WORDS, "de", value, problems));
	}

	@Test
	void aBatchAnswerIsAcceptedWordByWord() {
		StructuredResponse<Batch> response = batch("hund-laufen-schoen.json");

		assertThat(response.attempts()).isEqualTo(1);
		List<Word> words = response.value().words();
		assertThat(words).extracting(Word::partOfSpeech)
			.containsExactly(PartOfSpeech.NOUN, PartOfSpeech.VERB, PartOfSpeech.ADJECTIVE);
		assertThat(words.get(0).gender()).isEqualTo(Gender.MASCULINE);
		assertThat(words.get(0).plural()).isEqualTo("Hunde");
		assertThat(words.get(1).verbForms().auxiliary()).isEqualTo(Auxiliary.SEIN);
		assertThat(words.get(1).examples()).hasSize(3);
		assertThat(words.get(2).cefrLevel()).isEqualTo(CefrLevel.A1);
	}

	@Test
	void aBatchBreakingTheRulesIsSentBackWithEveryProblem() {
		InvalidLlmOutputException failure = catchThrowableOfType(InvalidLlmOutputException.class,
				() -> batch("invalid-plural-article-order-verb-forms.json"));

		assertThat(failure.problems()).anySatisfy(problem -> assertThat(problem).startsWith("words[0].plural"))
			.anySatisfy(problem -> assertThat(problem).startsWith("words[1].word"))
			.anySatisfy(problem -> assertThat(problem).startsWith("words[2].word"))
			.anySatisfy(problem -> assertThat(problem).startsWith("words[2].verbForms"));
	}

	@Test
	void aSingleWordAnswerIsAccepted() {
		Input haus = new Input("Haus", PartOfSpeech.NOUN);
		Word word = SampleAnswers.llmAnswering(SampleAnswers.read("vocabulary-enrichment/haus.json"))
			.complete(AiCaller.system(), AiUseCase.VOCABULARY_ENRICHMENT, PROMPT, Word.class,
					(value, problems) -> EnrichmentRules.check("", haus, "de", value, problems))
			.value();

		assertThat(word.gender()).isEqualTo(Gender.NEUTER);
		assertThat(word.plural()).isEqualTo("Häuser");
		assertThat(word.notes()).contains("zu Hause");
	}

}
