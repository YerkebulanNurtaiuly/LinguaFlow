package com.linguaflow.common.error;

/**
 * Base class for expected business errors. The handler turns it into a ProblemDetail
 * with the code's status; {@code detail} is shown to the client, so keep it free of
 * internal information.
 */
public class ApiException extends RuntimeException {

	private final ErrorCode code;

	public ApiException(ErrorCode code, String detail) {
		super(detail);
		this.code = code;
	}

	public ErrorCode code() {
		return code;
	}

}
