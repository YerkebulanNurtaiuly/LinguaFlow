package com.linguaflow.auth;

import java.time.Duration;

import com.linguaflow.common.config.LinguaFlowProperties;
import com.linguaflow.common.mail.Email;
import com.linguaflow.common.mail.Mailer;
import com.linguaflow.user.User;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** Texts of the account emails. Links point at frontend pages that call the API. */
@Component
class AccountEmails {

	private final Mailer mailer;

	private final String frontendUrl;

	AccountEmails(Mailer mailer, LinguaFlowProperties properties) {
		this.mailer = mailer;
		this.frontendUrl = properties.app().frontendUrl();
	}

	void sendVerification(User user, String token, Duration validFor) {
		mailer.send(new Email(user.getEmail(), "Confirm your email for LinguaFlow", """
				Hello %s,

				please confirm your email address by opening this link:
				%s

				The link is valid for %d hours. If you did not create a LinguaFlow account, ignore this email.
				""".formatted(user.getDisplayName(), link("/verify-email", token), validFor.toHours())));
	}

	void sendPasswordReset(User user, String token, Duration validFor) {
		mailer.send(new Email(user.getEmail(), "Reset your LinguaFlow password", """
				Hello %s,

				someone asked to reset the password of your LinguaFlow account. To choose a new one, open:
				%s

				The link is valid for %d minutes and works once. If it was not you, ignore this email;
				your password stays unchanged.
				""".formatted(user.getDisplayName(), link("/reset-password", token), validFor.toMinutes())));
	}

	private String link(String path, String token) {
		return UriComponentsBuilder.fromUriString(frontendUrl).path(path).queryParam("token", token).toUriString();
	}

}
