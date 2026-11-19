package com.linguaflow.common.mail;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpMailerTests {

	private final JavaMailSender sender = mock(JavaMailSender.class);

	private final SmtpMailer mailer = new SmtpMailer(sender, "LinguaFlow <bot@gmail.com>");

	@Test
	void sendsMessageWithFromToSubjectAndBody() {
		mailer.send(new Email("learner@example.com", "Confirm your email", "Open https://app/verify?token=abc"));

		ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(sender).send(captor.capture());
		SimpleMailMessage sent = captor.getValue();
		assertThat(sent.getFrom()).isEqualTo("LinguaFlow <bot@gmail.com>");
		assertThat(sent.getTo()).containsExactly("learner@example.com");
		assertThat(sent.getSubject()).isEqualTo("Confirm your email");
		assertThat(sent.getText()).contains("verify?token=abc");
	}

	@Test
	void swallowsSendFailureSoTheRequestStillSucceeds() {
		doThrow(new MailSendException("smtp down")).when(sender).send(any(SimpleMailMessage.class));

		assertThatCode(() -> mailer.send(new Email("learner@example.com", "Reset your password", "link")))
			.doesNotThrowAnyException();
	}

}
