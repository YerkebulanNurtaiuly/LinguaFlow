package com.linguaflow.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Stable, machine-readable error codes returned in the {@code code} field of every
 * error response. Clients should branch on these, never on the human-readable text.
 */
public enum ErrorCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
	MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
	BAD_REQUEST(HttpStatus.BAD_REQUEST, "Bad request"),
	INVALID_TOKEN(HttpStatus.BAD_REQUEST, "Invalid or expired link"),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid email or password"),
	INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Invalid refresh token"),
	FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
	ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "Account disabled"),
	NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
	NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Not acceptable"),
	CONFLICT(HttpStatus.CONFLICT, "Conflict"),
	EMAIL_TAKEN(HttpStatus.CONFLICT, "Email already registered"),
	NO_ACTIVE_LANGUAGE(HttpStatus.CONFLICT, "No active language"),
	VOCABULARY_DUPLICATE(HttpStatus.CONFLICT, "Vocabulary item already exists"),
	TAG_NAME_TAKEN(HttpStatus.CONFLICT, "Tag name already used"),
	GRAMMAR_TOPIC_DUPLICATE(HttpStatus.CONFLICT, "Grammar topic already exists"),
	PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Payload too large"),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
	TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
	AI_QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Daily AI limit reached"),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),
	SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable"),
	AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI unavailable");

	private final HttpStatus status;

	private final String title;

	ErrorCode(HttpStatus status, String title) {
		this.status = status;
		this.title = title;
	}

	public HttpStatus status() {
		return status;
	}

	public String title() {
		return title;
	}

	/** Default code for a status when no more specific code was chosen. */
	public static ErrorCode fromStatus(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> BAD_REQUEST;
			case 401 -> UNAUTHORIZED;
			case 403 -> FORBIDDEN;
			case 404 -> NOT_FOUND;
			case 405 -> METHOD_NOT_ALLOWED;
			case 406 -> NOT_ACCEPTABLE;
			case 409 -> CONFLICT;
			case 413 -> PAYLOAD_TOO_LARGE;
			case 415 -> UNSUPPORTED_MEDIA_TYPE;
			case 429 -> TOO_MANY_REQUESTS;
			case 503 -> SERVICE_UNAVAILABLE;
			default -> status.is4xxClientError() ? BAD_REQUEST : INTERNAL_ERROR;
		};
	}

}
