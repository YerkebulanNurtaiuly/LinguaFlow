package com.linguaflow.common.config;

import java.util.List;

import io.sentry.SentryEvent;
import io.sentry.protocol.Mechanism;
import io.sentry.protocol.SentryException;
import io.sentry.spring7.SentryExceptionResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-04: Sentry gets what the app logs as errors, not every exception a controller throws. */
class SentryConfigTests {

	private static SentryEvent eventWithMechanism(String type) {
		Mechanism mechanism = new Mechanism();
		mechanism.setType(type);
		SentryException exception = new SentryException();
		exception.setMechanism(mechanism);
		SentryEvent event = new SentryEvent();
		event.setExceptions(List.of(exception));
		return event;
	}

	@Test
	void eventsFromTheMvcExceptionResolverAreDropped() {
		var callback = new SentryConfig().dropResolverEvents();

		assertThat(callback.execute(eventWithMechanism(SentryExceptionResolver.MECHANISM_TYPE), null)).isNull();
	}

	@Test
	void loggedErrorsGoThrough() {
		var callback = new SentryConfig().dropResolverEvents();
		SentryEvent logged = eventWithMechanism("LogbackSentryAppender");

		assertThat(callback.execute(logged, null)).isSameAs(logged);
		SentryEvent plain = new SentryEvent();
		assertThat(callback.execute(plain, null)).isSameAs(plain);
	}

}
