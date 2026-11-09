package com.linguaflow.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class LinguaFlowPropertiesTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
		.withUserConfiguration(Config.class);

	@Test
	void bindsValidSettings() {
		runner.withPropertyValues("linguaflow.security.jwt.secret=" + "x".repeat(32), "linguaflow.ai.api-key=sk-test")
			.run(context -> {
				assertThat(context).hasNotFailed();
				LinguaFlowProperties props = context.getBean(LinguaFlowProperties.class);
				assertThat(props.security().jwt().secret()).hasSize(32);
				assertThat(props.ai().hasApiKey()).isTrue();
			});
	}

	@Test
	void blankAiKeyIsAllowed() {
		runner.withPropertyValues("linguaflow.security.jwt.secret=" + "x".repeat(32), "linguaflow.ai.api-key=")
			.run(context -> assertThat(context.getBean(LinguaFlowProperties.class).ai().hasApiKey()).isFalse());
	}

	@Test
	void failsWhenJwtSecretMissing() {
		runner.withPropertyValues("linguaflow.ai.api-key=").run(context -> assertThat(context).hasFailed());
	}

	@Test
	void failsWhenJwtSecretTooShort() {
		runner.withPropertyValues("linguaflow.security.jwt.secret=short", "linguaflow.ai.api-key=")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void failsWithClearMessageWhenPlaceholderUnresolved() {
		runner.withPropertyValues("linguaflow.security.jwt.secret=${JWT_SECRET}", "linguaflow.ai.api-key=")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("JWT_SECRET environment variable"));
	}

	@EnableConfigurationProperties(LinguaFlowProperties.class)
	static class Config {

	}

}
