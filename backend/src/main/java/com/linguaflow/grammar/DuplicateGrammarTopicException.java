package com.linguaflow.grammar;

import java.util.Map;
import java.util.UUID;

import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ErrorCode;

/** The user already has a topic with this title in the language: 409 GRAMMAR_TOPIC_DUPLICATE. */
public class DuplicateGrammarTopicException extends ApiException {

	private final UUID existingId;

	public DuplicateGrammarTopicException(String title, UUID existingId) {
		super(ErrorCode.GRAMMAR_TOPIC_DUPLICATE, "You already have a topic called \"" + title + "\"",
				Map.of("existingId", existingId));
		this.existingId = existingId;
	}

	public UUID existingId() {
		return existingId;
	}

}
