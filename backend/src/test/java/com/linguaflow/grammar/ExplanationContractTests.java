package com.linguaflow.grammar;

import com.linguaflow.ai.AiCaller;
import com.linguaflow.ai.AiUseCase;
import com.linguaflow.ai.LlmPrompt;
import com.linguaflow.ai.OutputCheck;
import com.linguaflow.ai.StructuredResponse;
import com.linguaflow.testsupport.SampleAnswers;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract of grammar explanations (QA-03): a sample answer through {@link ExplanationAnswer}. */
class ExplanationContractTests {

	@Test
	void anExplanationIsAcceptedWithItsTableIntact() {
		StructuredResponse<ExplanationAnswer> response = SampleAnswers
			.llmAnswering(SampleAnswers.read("grammar-explanation/dativ.json"))
			.complete(AiCaller.system(), AiUseCase.GRAMMAR_EXPLANATION, new LlmPrompt("system", "user"),
					ExplanationAnswer.class, OutputCheck.none());

		assertThat(response.attempts()).isEqualTo(1);
		ExplanationAnswer answer = response.value();
		assertThat(answer.rules()).hasSize(4);
		// Rules may hold plain-text tables, so their line breaks must survive the clean-up
		assertThat(answer.rules().get(1)).contains("\nfeminine   der / einer\n");
		assertThat(answer.examples()).hasSize(3);
		assertThat(answer.commonMistakes()).hasSize(2);
	}

}
