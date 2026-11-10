package com.linguaflow.common.error;

import java.util.Map;

/**
 * Base class for expected business errors. The handler turns it into a ProblemDetail
 * with the code's status; {@code detail} is shown to the client, so keep it free of
 * internal information. Extra {@code properties} are added to the problem body as
 * top-level fields (document each one in {@link ApiProblem}).
 */
public class ApiException extends RuntimeException {

	private final ErrorCode code;

	private final Map<String, Object> properties;

	public ApiException(ErrorCode code, String detail) {
		this(code, detail, Map.of());
	}

	public ApiException(ErrorCode code, String detail, Map<String, Object> properties) {
		super(detail);
		this.code = code;
		this.properties = Map.copyOf(properties);
	}

	public ErrorCode code() {
		return code;
	}

	public Map<String, Object> properties() {
		return properties;
	}

}
