package com.linguaflow.common.mail;

import java.util.Properties;

import com.linguaflow.common.config.LinguaFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * {@code linguaflow.mail.mode=log}: emails, including their links, are written to the log
 * (dev/test). {@code disabled} (default): nothing is sent and only the recipient-free fact
 * is logged. {@code smtp}: sent over SMTP with STARTTLS (for example Gmail with an App
 * Password); requires {@code username} and {@code password}. Tests can replace the bean (it
 * is {@code @ConditionalOnMissingBean}).
 */
@Configuration(proxyBeanMethods = false)
public class MailConfig {

	private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

	@Bean
	@ConditionalOnMissingBean
	Mailer mailer(LinguaFlowProperties properties) {
		LinguaFlowProperties.Mail mail = properties.mail();
		return switch (mail.mode()) {
			case "log" -> email -> log.info("Email (not sent, mail.mode=log) to {}: {}\n{}", email.to(),
					email.subject(), email.body());
			case "disabled" -> email -> log.info("Email '{}' suppressed (mail.mode=disabled)", email.subject());
			case "smtp" -> new SmtpMailer(smtpSender(mail), mail.from());
			default -> throw new IllegalStateException("Unknown linguaflow.mail.mode: " + mail.mode());
		};
	}

	/**
	 * Builds the SMTP sender from the mail settings. A missing username or password is a
	 * startup failure with a clear message, rather than a silent send failure later.
	 */
	static JavaMailSenderImpl smtpSender(LinguaFlowProperties.Mail mail) {
		if (mail.username() == null || mail.username().isBlank() || mail.password() == null
				|| mail.password().isBlank()) {
			throw new IllegalStateException(
					"linguaflow.mail.mode=smtp needs MAIL_USERNAME and MAIL_PASSWORD (e.g. a Gmail App Password)");
		}
		JavaMailSenderImpl sender = new JavaMailSenderImpl();
		sender.setHost(mail.host());
		sender.setPort(mail.port());
		sender.setUsername(mail.username());
		sender.setPassword(mail.password());
		Properties props = sender.getJavaMailProperties();
		props.put("mail.transport.protocol", "smtp");
		props.put("mail.smtp.auth", "true");
		props.put("mail.smtp.starttls.enable", String.valueOf(mail.startTls()));
		return sender;
	}

}
