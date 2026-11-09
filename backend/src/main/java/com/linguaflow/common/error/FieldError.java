package com.linguaflow.common.error;

/** One entry of the {@code errors} array in a VALIDATION_FAILED response. */
public record FieldError(String field, String message) {
}
