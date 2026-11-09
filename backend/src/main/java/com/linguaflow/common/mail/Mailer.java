package com.linguaflow.common.mail;

/** Outgoing mail. Pick the implementation with {@code linguaflow.mail.mode} (MailConfig). */
public interface Mailer {

	void send(Email email);

}
