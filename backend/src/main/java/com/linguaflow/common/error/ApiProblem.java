package com.linguaflow.common.error;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Documentation-only model of the error body produced by {@link GlobalExceptionHandler}
 * (a ProblemDetail plus LinguaFlow's extra properties). It is published in the OpenAPI
 * spec so the frontend gets a generated type for API errors. Keep it in sync with the
 * handler.
 */
@Schema(name = "ApiProblem", description = "RFC 7807 problem details returned for every error")
public record ApiProblem(
		@Schema(description = "Problem type URI", example = "about:blank") URI type,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Validation failed") String title,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "400") int status,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				example = "One or more fields are invalid") String detail,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "/api/vocabulary") URI instance,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Stable machine-readable error code") ErrorCode code,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "ID of the request; quote it when reporting problems") String correlationId,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant timestamp,
		@Schema(description = "Present for VALIDATION_FAILED: one entry per invalid field") List<FieldError> errors,
		@Schema(description = "Present for VOCABULARY_DUPLICATE, TAG_NAME_TAKEN and GRAMMAR_TOPIC_DUPLICATE: ID of the word, tag or topic that already exists") UUID existingId) {
}
