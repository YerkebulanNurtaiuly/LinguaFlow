package com.linguaflow.ai;

/**
 * Domain checks on a parsed AI answer that Bean Validation can't express, e.g. "the
 * article matches the gender" (AI-02). Report each problem to {@code problems}; an
 * answer with problems is retried once with them as feedback.
 */
@FunctionalInterface
public interface OutputCheck<T> {

	OutputCheck<Object> NONE = (value, problems) -> {
	};

	void check(T value, OutputProblems problems);

	@SuppressWarnings("unchecked")
	static <T> OutputCheck<T> none() {
		return (OutputCheck<T>) NONE;
	}

}
