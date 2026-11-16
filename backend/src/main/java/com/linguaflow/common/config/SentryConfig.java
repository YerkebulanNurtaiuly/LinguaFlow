package com.linguaflow.common.config;

import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Mechanism;
import io.sentry.protocol.SentryException;
import io.sentry.spring7.SentryExceptionResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Error tracking with Sentry (OPS-04). It's off unless {@code SENTRY_DSN} is set.
 * <p>
 * Sentry receives what the app logs at ERROR (the Logback integration,
 * {@code sentry.logging.minimum-event-level}): unhandled 500s ({@code GlobalExceptionHandler}),
 * dead AI jobs, failed event handlers, and browser errors the frontend reports
 * ({@code ClientErrorController}). Sentry's own MVC exception resolver would also report
 * every exception a controller throws, including the deliberate 404s, 409s and validation
 * errors, and the 500s a second time, so its events are dropped here.
 * {@code send-default-pii} stays off: no IP addresses, cookies or request bodies are sent.
 */
@Configuration(proxyBeanMethods = false)
class SentryConfig {

	@Bean
	SentryOptions.BeforeSendCallback dropResolverEvents() {
		return (event, hint) -> fromExceptionResolver(event) ? null : event;
	}

	static boolean fromExceptionResolver(SentryEvent event) {
		if (event.getExceptions() == null) {
			return false;
		}
		for (SentryException exception : event.getExceptions()) {
			Mechanism mechanism = exception.getMechanism();
			if (mechanism != null && SentryExceptionResolver.MECHANISM_TYPE.equals(mechanism.getType())) {
				return true;
			}
		}
		return false;
	}

}
