package com.linguaflow.vocabulary;

import java.util.Map;
import java.util.UUID;

import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ErrorCode;

/**
 * The user already has this word (same normalized lemma and part of speech in the same
 * language). Rendered as 409 VOCABULARY_DUPLICATE with the existing item's ID in
 * {@code existingId}, so the client can link to it.
 */
public class DuplicateVocabularyItemException extends ApiException {

	private final UUID existingId;

	public DuplicateVocabularyItemException(String lemma, UUID existingId) {
		super(ErrorCode.VOCABULARY_DUPLICATE, "\"" + lemma + "\" is already in your vocabulary",
				Map.of("existingId", existingId));
		this.existingId = existingId;
	}

	public UUID existingId() {
		return existingId;
	}

}
