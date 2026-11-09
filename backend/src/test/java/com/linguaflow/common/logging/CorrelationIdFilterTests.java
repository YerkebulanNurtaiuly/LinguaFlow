package com.linguaflow.common.logging;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTests {

	@ParameterizedTest
	@ValueSource(strings = { "abc", "req-42_x.y", "0123456789012345678901234567890123456789012345678901234567890123" })
	void keepsWellFormedIds(String id) {
		assertThat(CorrelationIdFilter.resolveCorrelationId(id)).isEqualTo(id);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "has space", "line\nbreak", "<script>",
			"01234567890123456789012345678901234567890123456789012345678901234" })
	void replacesMissingOrUnsafeIds(String id) {
		String resolved = CorrelationIdFilter.resolveCorrelationId(id);
		assertThat(resolved).isNotEqualTo(id).hasSize(36);
	}

}
