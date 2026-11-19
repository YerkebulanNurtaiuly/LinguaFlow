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

	@Test
	void smtpModeNeedsCredentials() {
		runner.withPropertyValues("linguaflow.mail.mode=smtp").run(context -> assertThat(context).hasFailed());
	}

	@Test
	void smtpModeNeedsPasswordEvenWithUsername() {
		runner.withPropertyValues("linguaflow.mail.mode=smtp", "linguaflow.mail.username=bot@gmail.com")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void smtpModeBuildsSmtpMailer() {
		runner
			.withPropertyValues("linguaflow.mail.mode=smtp", "linguaflow.mail.username=bot@gmail.com",
					"linguaflow.mail.password=app-password")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(Mailer.class)).isInstanceOf(SmtpMailer.class);
			});
	}

	@Test
	void smtpSenderUsesConfiguredHostAndPort() {
		var mail = new LinguaFlowProperties.Mail("smtp", "LinguaFlow <bot@gmail.com>", "smtp.example.com", 2525,
				"bot@gmail.com", "app-password", true);
		var sender = MailConfig.smtpSender(mail);
		assertThat(sender.getHost()).isEqualTo("smtp.example.com");
		assertThat(sender.getPort()).isEqualTo(2525);
		assertThat(sender.getUsername()).isEqualTo("bot@gmail.com");
		assertThat(sender.getJavaMailProperties()).containsEntry("mail.smtp.auth", "true")
			.containsEntry("mail.smtp.starttls.enable", "true");
	}

	@EnableConfigurationProperties(LinguaFlowProperties.class)
	static class Props {

	}

}
