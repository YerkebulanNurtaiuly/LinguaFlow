package com.linguaflow.common.error;

/**
 * A business-rule check on one request field failed (e.g. "current password is
 * incorrect"). Rendered exactly like a Bean Validation failure: 400 VALIDATION_FAILED
 * with {@code errors: [{field, message}]}, so forms can show it next to the input.
 */
public class InvalidFieldException extends ApiException {

	private final String field;

	public InvalidFieldException(String field, String message) {
		super(ErrorCode.VALIDATION_FAILED, message);
		this.field = field;
	}

	public String field() {
		return field;
	}

}
