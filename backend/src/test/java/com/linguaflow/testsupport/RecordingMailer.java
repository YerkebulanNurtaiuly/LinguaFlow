package com.linguaflow.testsupport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.linguaflow.common.mail.Email;
import com.linguaflow.common.mail.Mailer;

/** Captures sent emails; register it as a {@code @Primary} Mailer bean in tests. */
public class RecordingMailer implements Mailer {

	private static final Pattern TOKEN = Pattern.compile("[?&]token=([A-Za-z0-9_-]+)");

	private final List<Email> sent = new CopyOnWriteArrayList<>();

	@Override
	public void send(Email email) {
		sent.add(email);
	}

	public List<Email> sentTo(String address) {
		return sent.stream().filter(e -> e.to().equalsIgnoreCase(address)).toList();
	}

	public Email lastTo(String address) {
		List<Email> emails = sentTo(address);
		if (emails.isEmpty()) {
			throw new AssertionError("No email sent to " + address);
		}
		return emails.get(emails.size() - 1);
	}

	/** The token from the link in the most recent email to {@code address}. */
	public String lastTokenFor(String address) {
		Matcher matcher = TOKEN.matcher(lastTo(address).body());
		if (!matcher.find()) {
			throw new AssertionError("No link with a token in the last email to " + address);
		}
		return matcher.group(1);
	}

	public void clear() {
		sent.clear();
	}

}
