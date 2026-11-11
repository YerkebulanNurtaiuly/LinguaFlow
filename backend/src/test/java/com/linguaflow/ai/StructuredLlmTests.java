package com.linguaflow.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import com.linguaflow.ai.LlmOptions.Effort;
import com.linguaflow.ai.cache.AiCache;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StructuredLlmTests {

	private static final LlmOptions OPTIONS = new LlmOptions(AiUseCase.VOCABULARY_ENRICHMENT, "claude-opus-5-5",
			Effort.LOW, 16_000, Duration.ofMinutes(1));

	private static final LlmPrompt PROMPT = new LlmPrompt("You enrich German words.", "Hund");

	private static final List<String> ARTICLES = List.of("der", "die", "das");

	enum Gender {

		MASCULINE, FEMININE, NEUTER

	}

	record Enrichment(@NotBlank @Size(max = 50) String translation, @Nullable Gender gender,
			@Nullable String article, @Size(min = 1, max = 3) List<@Valid Example> examples) {
	}

	record Example(@NotBlank String sentence, @Nullable String translation) {
	}

	/** The domain check from the task: article ∈ {der, die, das}, matching the gender. */
	private static final OutputCheck<Enrichment> ARTICLE = (word, problems) -> {
		problems.requireOneOf("article", word.article(), ARTICLES);
		if (word.gender() != null && word.article() != null && ARTICLES.contains(word.article())) {
			String expected = switch (word.gender()) {
				case MASCULINE -> "der";
				case FEMININE -> "die";
				case NEUTER -> "das";
			};
			if (!expected.equals(word.article())) {
				problems.add("article", "doesn't match the gender " + word.gender());
			}
		}
	};

	private static final String VALID = """
			{"translation":"dog","gender":"MASCULINE","article":"der",
			 "examples":[{"sentence":"Der Hund bellt.","translation":"The dog barks."}]}""";

	private final FakeLlmClient fake = new FakeLlmClient();

	private final Deque<String> answers = new ArrayDeque<>();

	private StructuredLlm llm;

	@BeforeEach
	void setUp() {
		AiSettings settings = mock(AiSettings.class);
		when(settings.options(any())).thenReturn(OPTIONS);
		llm = new StructuredLlm(TestGateways.unlimited(fake), mock(AiCache.class), settings,
				Validation.buildDefaultValidatorFactory().getValidator());
		fake.respondTo(AiUseCase.VOCABULARY_ENRICHMENT, prompt -> answers.isEmpty() ? VALID : answers.poll());
	}

	private StructuredResponse<Enrichment> enrich() {
		return llm.complete(AiCaller.system(), AiUseCase.VOCABULARY_ENRICHMENT, PROMPT, Enrichment.class, ARTICLE);
	}

	@Test
	void returnsAValidAnswerAfterOneCall() {
		StructuredResponse<Enrichment> response = enrich();

		assertThat(response.value().translation()).isEqualTo("dog");
		assertThat(response.value().examples()).extracting(Example::sentence).containsExactly("Der Hund bellt.");
		assertThat(response.attempts()).isEqualTo(1);
		assertThat(response.inputTokens()).isPositive();
		// The record's schema goes with the call
		assertThat(fake.calls()).singleElement()
			.satisfies(call -> assertThat(call.jsonSchema()).isEqualTo(llm.schemaFor(Enrichment.class)));
	}

	@Test
	void retriesOnceWithTheProblemsAsFeedback() {
		answers.add("""
				{"translation":"dog","gender":"MASCULINE","article":"dem",
				 "examples":[{"sentence":"Der Hund bellt.","translation":null}]}""");

		StructuredResponse<Enrichment> response = enrich();

		assertThat(response.value().article()).isEqualTo("der");
		assertThat(response.attempts()).isEqualTo(2);
		LlmPrompt retry = fake.calls().get(1).prompt();
		// Same system prompt, so the cached prefix is reused
		assertThat(retry.system()).isEqualTo(PROMPT.system());
		assertThat(retry.user()).startsWith("Hund")
			.contains("\"article\":\"dem\"")
			.contains("- article: must be one of der, die, das, not \"dem\"")
			.endsWith("fixing every problem.");
	}

	@Test
	void givesUpAfterTheSecondInvalidAnswer() {
		answers.add("{\"translation\":\"\",\"gender\":null,\"article\":null,\"examples\":[]}");
		answers.add("""
				{"translation":"dog","gender":"FEMININE","article":"der",
				 "examples":[{"sentence":"x","translation":null}]}""");

		assertThatThrownBy(this::enrich).isInstanceOfSatisfying(InvalidLlmOutputException.class, ex -> {
			assertThat(ex.kind()).isEqualTo(LlmException.Kind.INVALID_OUTPUT);
			assertThat(ex.kind().retryable()).isFalse();
			assertThat(ex.problems()).containsExactly("article: doesn't match the gender FEMININE");
			assertThat(ex.calls()).hasSize(2);
		});
		// The first answer's Bean Validation problems were fed back
		assertThat(fake.calls().get(1).prompt().user()).contains("- examples: size must be between 1 and 3")
			.contains("- translation: must not be blank");
	}

	@Test
	void reportsMissingFieldsAtAnyDepth() {
		answers.add("{\"gender\":\"MASCULINE\",\"article\":\"der\",\"examples\":[{\"translation\":\"x\"},null]}");

		enrich();

		assertThat(fake.calls().get(1).prompt().user()).contains("- translation: is missing")
			.contains("- examples[0].sentence: is missing")
			.contains("- examples[1]: must not be null");
	}

	@Test
	void reportsAnswersThatAreNotJsonForTheSchema() {
		answers.add("Sure! Here is the word: dog");
		answers.add("{\"translation\":\"dog\",\"gender\":\"MALE\",\"article\":\"der\",\"examples\":[]}");

		assertThatThrownBy(this::enrich).isInstanceOfSatisfying(InvalidLlmOutputException.class,
				ex -> assertThat(ex.problems()).singleElement()
					.asString()
					.contains("isn't valid JSON for the schema at gender: ")
					.doesNotContain("com.linguaflow", "$")
					.contains("gender")
					.contains("MALE"));
		assertThat(fake.calls().get(1).prompt().user()).contains("- the answer isn't valid JSON for the schema");
	}

	@Test
	void acceptsJsonInACodeFenceAndIgnoresUnknownProperties() {
		answers.add("```json\n" + VALID.replace("{\"translation\"", "{\"confidence\":0.9,\"translation\"") + "\n```");

		assertThat(enrich().attempts()).isEqualTo(1);
	}

	@Test
	void providerErrorsPassThroughWithoutARetry() {
		fake.failNext(new LlmException(LlmException.Kind.RATE_LIMITED, "slow down"));

		assertThatThrownBy(this::enrich).isInstanceOfSatisfying(LlmException.class,
				ex -> assertThat(ex.kind()).isEqualTo(LlmException.Kind.RATE_LIMITED));
		assertThat(fake.calls()).hasSize(1);
	}

	@Test
	void quotesLongRejectedAnswersShortened() {
		LlmPrompt retry = StructuredLlm.withFeedback(PROMPT, "x".repeat(10_000), List.of("translation: is missing"));
		assertThat(retry.user()).contains("x".repeat(StructuredLlm.MAX_QUOTED_ANSWER) + "…")
			.doesNotContain("x".repeat(StructuredLlm.MAX_QUOTED_ANSWER + 1));
	}

}
