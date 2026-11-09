package com.linguaflow.common.error;

import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.linguaflow.common.logging.CorrelationIdFilter;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 7807 {@link ProblemDetail} with the same shape:
 * {@code type, title, status, detail, instance, code, correlationId, timestamp} plus
 * {@code errors} for validation failures. Spring MVC exceptions are handled by the
 * superclass and pass through {@link #handleExceptionInternal}; security exceptions are
 * routed here from the filter chain (see SecurityConfig).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private static final String CODE = "code";

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
		ProblemDetail body = problem(ex.code(), ex.getMessage());
		return handleExceptionInternal(ex, body, new HttpHeaders(), ex.code().status(), request);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
		List<FieldError> errors = ex.getConstraintViolations()
			.stream()
			.map(v -> new FieldError(v.getPropertyPath().toString(), v.getMessage()))
			.sorted(Comparator.comparing(FieldError::field))
			.toList();
		return validationFailed(ex, errors, request);
	}

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
		// RFC 6750: tell bearer clients why; a rejected token gets error="invalid_token"
		boolean badToken = ex instanceof OAuth2AuthenticationException;
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.WWW_AUTHENTICATE, badToken ? "Bearer error=\"invalid_token\"" : "Bearer");
		ProblemDetail body = problem(ErrorCode.UNAUTHORIZED,
				badToken ? "Access token is invalid or expired" : "Full authentication is required to access this resource");
		return handleExceptionInternal(ex, body, headers, ErrorCode.UNAUTHORIZED.status(), request);
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
		ProblemDetail body = problem(ErrorCode.FORBIDDEN, "You do not have permission to access this resource");
		return handleExceptionInternal(ex, body, new HttpHeaders(), ErrorCode.FORBIDDEN.status(), request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
		log.error("Unhandled exception", ex);
		ProblemDetail body = problem(ErrorCode.INTERNAL_ERROR,
				"An unexpected error occurred. Quote the correlationId when reporting it.");
		return handleExceptionInternal(ex, body, new HttpHeaders(), ErrorCode.INTERNAL_ERROR.status(), request);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return validationFailed(ex, toFieldErrors(ex.getBindingResult()), request);
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldError> errors = ex.getParameterValidationResults()
			.stream()
			.flatMap(result -> result.getResolvableErrors()
				.stream()
				.map(error -> new FieldError(result.getMethodParameter().getParameterName(),
						error.getDefaultMessage())))
			.toList();
		return validationFailed(ex, errors, request);
	}

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		// The parser message can echo internals (class names, positions); keep it generic
		ProblemDetail body = problem(ErrorCode.MALFORMED_REQUEST, "Request body is missing or not valid JSON");
		return handleExceptionInternal(ex, body, headers, status, request);
	}

	/**
	 * Single exit point: every response produced by this handler gets the common fields.
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
			HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
		ProblemDetail problem = (body instanceof ProblemDetail pd) ? pd
				: ProblemDetail.forStatusAndDetail(statusCode, ex.getMessage());
		enrich(problem, statusCode, request);
		if (statusCode.is4xxClientError()) {
			log.debug("Request failed with {} {}: {}", statusCode.value(), problem.getProperties().get(CODE),
					ex.getMessage());
		}
		return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
	}

	private ResponseEntity<Object> validationFailed(Exception ex, List<FieldError> errors, WebRequest request) {
		ProblemDetail body = problem(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid");
		body.setProperty("errors", errors);
		return handleExceptionInternal(ex, body, new HttpHeaders(), ErrorCode.VALIDATION_FAILED.status(), request);
	}

	private static List<FieldError> toFieldErrors(BindingResult result) {
		List<FieldError> fieldErrors = result.getFieldErrors()
			.stream()
			.map(e -> new FieldError(e.getField(), e.getDefaultMessage()))
			.sorted(Comparator.comparing(FieldError::field))
			.toList();
		List<FieldError> globalErrors = result.getGlobalErrors()
			.stream()
			.map(e -> new FieldError(e.getObjectName(), e.getDefaultMessage()))
			.toList();
		return Stream.concat(fieldErrors.stream(), globalErrors.stream()).toList();
	}

	private static ProblemDetail problem(ErrorCode code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
		problem.setTitle(code.title());
		problem.setProperty(CODE, code.name());
		return problem;
	}

	private static void enrich(ProblemDetail problem, HttpStatusCode status, WebRequest request) {
		ErrorCode code = ErrorCode.fromStatus(status);
		if (problem.getProperties() == null || !problem.getProperties().containsKey(CODE)) {
			problem.setProperty(CODE, code.name());
			problem.setTitle(code.title());
		}
		if (problem.getInstance() == null) {
			problem.setInstance(URI.create(request.getDescription(false).replaceFirst("^uri=", "")));
		}
		problem.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
		problem.setProperty("timestamp", Instant.now());
	}

}
