package com.linguaflow.common.mail;

public record Email(String to, String subject, String body) {

	@Override
	public String toString() {
		// Bodies can contain secret links; keep them out of incidental logging
		return "Email[to=" + to + ", subject=" + subject + "]";
	}

}
