package com.linguaflow.vocabulary;

import java.util.Map;
import java.util.UUID;

import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ErrorCode;

/**
 * The user already has a tag with this name (ignoring case) in the language. Rendered as
 * 409 TAG_NAME_TAKEN with the existing tag's ID in {@code existingId}.
 */
public class TagNameTakenException extends ApiException {

	private final UUID existingId;

	public TagNameTakenException(String name, UUID existingId) {
		super(ErrorCode.TAG_NAME_TAKEN, "You already have a tag called \"" + name + "\"",
				Map.of("existingId", existingId));
		this.existingId = existingId;
	}

	public UUID existingId() {
		return existingId;
	}

}
