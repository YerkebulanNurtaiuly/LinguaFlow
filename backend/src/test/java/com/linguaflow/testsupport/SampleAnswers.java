package com.linguaflow.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.linguaflow.ai.AiSettings;
import com.linguaflow.ai.AiUseCase;
import com.linguaflow.ai.FakeLlmClient;
import com.linguaflow.ai.LlmOptions;
import com.linguaflow.ai.StructuredLlm;
import com.linguaflow.ai.TestGateways;
import com.linguaflow.ai.cache.AiCache;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sample model answers for the AI contract tests (QA-03), from
 * {@code src/test/resources/ai-answers}, and a {@link StructuredLlm} that answers every
 * call with one of them, so a test runs the production parser, record and check on it.
 * <pre>{@code
 * StructuredLlm llm = SampleAnswers.llmAnswering(SampleAnswers.read("grammar-explanation/dativ.json"));
 * ExplanationAnswer answer = llm.complete(AiCaller.system(), prompt, ExplanationAnswer.class, check).value();
 * }</pre>
 * An invalid sample is answered again on the retry, so it ends in an
 * {@code InvalidLlmOutputException} whose problems are what the model would be told.
 */
public final class SampleAnswers {

	public static final Path DIRECTORY = Path.of("src", "test", "resources", "ai-answers");

	public static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

	private SampleAnswers() {
	}

	/** A sample's text, e.g. {@code read("answer-evaluation/correct.json")}. */
	public static String read(String name) {
		try {
			return Files.readString(DIRECTORY.resolve(name), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("No sample answer " + name, ex);
		}
	}

	/** A structured client whose model answers {@code text} to every call. */
	public static StructuredLlm llmAnswering(String text) {
		FakeLlmClient fake = new FakeLlmClient();
		for (AiUseCase useCase : AiUseCase.values()) {
			fake.respondTo(useCase, prompt -> text);
		}
		AiSettings settings = mock(AiSettings.class);
		when(settings.options(any())).thenAnswer(call -> new LlmOptions(call.getArgument(0), "fake-model",
				LlmOptions.Effort.LOW, 4_000, Duration.ofSeconds(30)));
		return new StructuredLlm(TestGateways.unlimited(fake), mock(AiCache.class), settings, VALIDATOR);
	}

}
