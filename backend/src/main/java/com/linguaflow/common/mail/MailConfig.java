package com.linguaflow.common.mail;

import com.linguaflow.common.config.LinguaFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code linguaflow.mail.mode=log}: emails, including their links, are written to the log
 * (dev/test). {@code disabled} (default, and prod): nothing is sent and only the
 * recipient-free fact is logged. Tests can replace the bean (it is
 * {@code @ConditionalOnMissingBean}).
 */
@Configuration(proxyBeanMethods = false)
public class MailConfig {

	private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

	@Bean
	@ConditionalOnMissingBean
	Mailer mailer(LinguaFlowProperties properties) {
		String mode = properties.mail().mode();
		return switch (mode) {
			case "log" -> email -> log.info("Email (not sent, mail.mode=log) to {}: {}\n{}", email.to(),
					email.subject(), email.body());
			case "disabled" -> email -> log.info("Email '{}' suppressed (mail.mode=disabled)", email.subject());
			default -> throw new IllegalStateException("Unknown linguaflow.mail.mode: " + mode);
		};
	}

}
