package com.linguaflow.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import com.linguaflow.ai.LlmOptions.Effort;
import com.linguaflow.common.config.LinguaFlowProperties;
import com.linguaflow.common.config.LinguaFlowProperties.Ai;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class AiSettingsTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
		.withUserConfiguration(Config.class)
		.withPropertyValues("linguaflow.security.jwt.secret=" + "x".repeat(32));

	@Configuration
	@EnableConfigurationProperties(LinguaFlowProperties.class)
	static class Config {

		@Bean
		AiSettings aiSettings(LinguaFlowProperties properties) {
			return new AiSettings(properties);
		}

		@Bean
		java.time.Clock clock() {
			return java.time.Clock.systemUTC();
		}

		@Bean
		LlmClient llmClient(LinguaFlowProperties properties, java.time.Clock clock) {
			return new AiConfig().llmClient(properties, clock);
		}

	}

	@Test
	void everyUseCaseHasSensibleDefaults() {
		runner.run(context -> {
			AiSettings settings = context.getBean(AiSettings.class);
			for (AiUseCase useCase : AiUseCase.values()) {
				assertThat(settings.options(useCase).model()).isEqualTo("claude-opus-5-5");
				assertThat(settings.options(useCase).useCase()).isEqualTo(useCase);
			}
			assertThat(settings.options(AiUseCase.VOCABULARY_ENRICHMENT).effort()).isEqualTo(Effort.LOW);
			assertThat(settings.options(AiUseCase.GRAMMAR_EXPLANATION).effort()).isEqualTo(Effort.MEDIUM);
			assertThat(settings.options(AiUseCase.EXERCISE_GENERATION).timeout()).isEqualTo(Duration.ofMinutes(3));
		});
	}

	@Test
	void appliesTheDefaultModelAndPerUseCaseOverrides() {
		runner
			.withPropertyValues("linguaflow.ai.model=claude-sonnet-5-5",
					"linguaflow.ai.use-cases.vocabulary-enrichment.model=claude-haiku-5-5",
					"linguaflow.ai.use-cases.vocabulary-enrichment.effort=medium",
					"linguaflow.ai.use-cases.vocabulary-enrichment.max-tokens=8000",
					"linguaflow.ai.use-cases.vocabulary-enrichment.timeout=30s")
			.run(context -> {
				AiSettings settings = context.getBean(AiSettings.class);
				LlmOptions enrichment = settings.options(AiUseCase.VOCABULARY_ENRICHMENT);
				assertThat(enrichment.model()).isEqualTo("claude-haiku-5-5");
				assertThat(enrichment.effort()).isEqualTo(Effort.MEDIUM);
				assertThat(enrichment.maxTokens()).isEqualTo(8000);
				assertThat(enrichment.timeout()).isEqualTo(Duration.ofSeconds(30));
				assertThat(settings.options(AiUseCase.GRAMMAR_EXPLANATION).model()).isEqualTo("claude-sonnet-5-5");
			});
	}

	@Test
	void failsAtStartupOnUnknownUseCasesOrEfforts() {
		runner.withPropertyValues("linguaflow.ai.use-cases.vocab.effort=low")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("Unknown AI use case"));
		runner.withPropertyValues("linguaflow.ai.use-cases.grammar-explanation.effort=extreme")
			.run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("Unknown effort 'extreme'"));
	}

	@Test
	void choosesTheClientFromTheProviderSetting() {
		runner.withPropertyValues("linguaflow.ai.api-key=")
			.run(context -> assertThat(context.getBean(LlmClient.class)).isInstanceOf(FakeLlmClient.class));
		runner.withPropertyValues("linguaflow.ai.api-key=sk-test")
			.run(context -> assertThat(context.getBean(LlmClient.class)).isInstanceOf(AnthropicLlmClient.class));
		runner.withPropertyValues("linguaflow.ai.api-key=sk-test", "linguaflow.ai.provider=fake")
			.run(context -> assertThat(context.getBean(LlmClient.class)).isInstanceOf(FakeLlmClient.class));
		runner.withPropertyValues("linguaflow.ai.api-key=", "linguaflow.ai.provider=anthropic")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("no API key"));
	}

	@Test
	void neverPrintsTheApiKey() {
		Ai ai = new Ai("sk-ant-secret", null, null, Map.of(), null, null, null, null, null);
		assertThat(ai.toString()).doesNotContain("sk-ant-secret").contains("apiKey=***");
		assertThat(ai.usesAnthropic()).isTrue();
	}

}
