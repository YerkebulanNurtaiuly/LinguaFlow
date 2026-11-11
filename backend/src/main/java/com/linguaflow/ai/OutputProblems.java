package com.linguaflow.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The problems found in one AI answer (AI-02), each as "path: message". They are sent
 * back to the model on the retry, so phrase them as instructions the model can act on.
 */
public final class OutputProblems {

	private final List<String> problems = new ArrayList<>();

	public OutputProblems add(String path, String message) {
		problems.add(path.isEmpty() ? message : path + ": " + message);
		return this;
	}

	/** Records a problem unless {@code value} is null or one of {@code allowed}. */
	public OutputProblems requireOneOf(String path, @Nullable String value, Collection<String> allowed) {
		if (value != null && !allowed.contains(value)) {
			add(path, "must be one of " + String.join(", ", allowed) + ", not \"" + value + "\"");
		}
		return this;
	}

	public boolean isEmpty() {
		return problems.isEmpty();
	}

	public List<String> list() {
		return List.copyOf(problems);
	}

}
