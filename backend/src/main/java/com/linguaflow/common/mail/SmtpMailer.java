package com.linguaflow.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Sends plain-text account emails over SMTP (MailConfig builds it in {@code smtp} mode). A
 * send failure is logged (without the body, which holds a secret link) and swallowed, so a
 * provider hiccup never fails the user's registration or password-reset request.
 */
class SmtpMailer implements Mailer {

	private static final Logger log = LoggerFactory.getLogger(SmtpMailer.class);

	private final JavaMailSender sender;

	private final String from;

	SmtpMailer(JavaMailSender sender, String from) {
		this.sender = sender;
		this.from = from;
	}

	@Override
	public void send(Email email) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(email.to());
		message.setSubject(email.subject());
		message.setText(email.body());
		try {
			sender.send(message);
		}
		catch (MailException ex) {
			log.error("Could not send email '{}' to {}: {}", email.subject(), email.to(), ex.getMessage());
		}
	}

}
