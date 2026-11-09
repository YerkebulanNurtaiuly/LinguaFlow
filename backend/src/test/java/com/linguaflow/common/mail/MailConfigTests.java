package com.linguaflow.common.mail;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.linguaflow.common.config.LinguaFlowProperties;

import static org.assertj.core.api.Assertions.assertThat;

class MailConfigTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(Props.class, MailConfig.class)
		.withPropertyValues("linguaflow.security.jwt.secret=" + "x".repeat(32), "linguaflow.ai.api-key=");

	@Test
	void disabledByDefault() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(LinguaFlowProperties.class).mail().mode()).isEqualTo("disabled");
			context.getBean(Mailer.class).send(new Email("a@b.de", "s", "secret link"));
		});
	}

	@Test
	void unknownModeFailsAtStartup() {
		runner.withPropertyValues("linguaflow.mail.mode=smtp-typo").run(context -> assertThat(context).hasFailed());
	}

	@EnableConfigurationProperties(LinguaFlowProperties.class)
	static class Props {

	}

}
