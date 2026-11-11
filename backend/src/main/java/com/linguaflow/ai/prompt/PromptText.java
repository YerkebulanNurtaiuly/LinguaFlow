package com.linguaflow.ai.prompt;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The template syntax (AI-03), a small subset of Mustache:
 * <ul>
 * <li>{@code {{name}}} inserts a variable.</li>
 * <li>{@code {{#name}}…{{/name}}} is kept only when the variable is set;
 * {@code {{^name}}…{{/name}}} only when it isn't. Different names may nest.</li>
 * <li>{@code {{> partial}}} includes {@code prompts/partials/<partial>.md}, resolved
 * when the library loads.</li>
 * </ul>
 * A tag alone on its line takes the line with it, and runs of blank lines collapse to
 * one, so optional sections leave no gaps. Inserted values are never parsed again.
 */
final class PromptText {

	private static final Pattern PARTIAL = Pattern.compile("(?m)^[ \\t]*\\{\\{>\\s*([a-z0-9-]+)\\s*}}[ \\t]*$");

	private static final Pattern STANDALONE_TAG = Pattern.compile("(?m)^[ \\t]*(\\{\\{[#^/]\\w+}})[ \\t]*\\n");

	private static final Pattern SECTION = Pattern.compile("\\{\\{([#^])(\\w+)}}(.*?)\\{\\{/\\2}}", Pattern.DOTALL);

	private static final Pattern VARIABLE = Pattern.compile("\\{\\{(\\w+)}}");

	private static final Pattern ANY_TAG_NAME = Pattern.compile("\\{\\{[#^/]?(\\w+)}}");

	private static final int MAX_PARTIAL_DEPTH = 3;

	private PromptText() {
	}

	/**
	 * Normalizes line endings (so a Windows checkout renders and hashes the same),
	 * resolves partials and removes the lines of standalone tags.
	 */
	static String prepare(String text, Function<String, String> partials) {
		String prepared = resolvePartials(text.replace("\r\n", "\n"), partials, 0);
		prepared = STANDALONE_TAG.matcher(prepared).replaceAll(match -> Matcher.quoteReplacement(match.group(1)));
		checkSyntax(prepared);
		return prepared;
	}

	private static String resolvePartials(String text, Function<String, String> partials, int depth) {
		Matcher matcher = PARTIAL.matcher(text);
		if (!matcher.find()) {
			return text;
		}
		if (depth >= MAX_PARTIAL_DEPTH) {
			throw new IllegalStateException("Partials nest deeper than " + MAX_PARTIAL_DEPTH);
		}
		return matcher.replaceAll(match -> Matcher.quoteReplacement(
				resolvePartials(partials.apply(match.group(1)).replace("\r\n", "\n").strip(), partials, depth + 1)));
	}

	/** Every tag is well formed and every section closed. */
	private static void checkSyntax(String text) {
		String rest = text;
		String previous;
		do {
			previous = rest;
			rest = SECTION.matcher(rest).replaceAll(match -> Matcher.quoteReplacement(match.group(3)));
		}
		while (!rest.equals(previous));
		rest = VARIABLE.matcher(rest).replaceAll("");
		int bad = Math.max(rest.indexOf("{{"), rest.indexOf("}}"));
		if (bad >= 0) {
			String excerpt = rest.substring(Math.max(0, bad - 30), Math.min(rest.length(), bad + 30));
			throw new IllegalStateException("Malformed or unclosed tag near: " + excerpt.replace('\n', ' '));
		}
	}

	/** The variable names a prepared text uses, in order of appearance. */
	static Set<String> variableNames(String text) {
		Set<String> names = new LinkedHashSet<>();
		ANY_TAG_NAME.matcher(text).results().forEach(match -> names.add(match.group(1)));
		return names;
	}

	/** Fills in a prepared text. Every name it uses must be a key or absent from the map. */
	static String render(String text, Map<String, String> values) {
		String rendered = text;
		String previous;
		do {
			previous = rendered;
			rendered = SECTION.matcher(rendered).replaceAll(match -> {
				boolean set = values.containsKey(match.group(2));
				boolean keep = match.group(1).equals("#") == set;
				return Matcher.quoteReplacement(keep ? match.group(3) : "");
			});
		}
		while (!rendered.equals(previous));
		rendered = VARIABLE.matcher(rendered).replaceAll(match -> {
			String value = values.get(match.group(1));
			if (value == null) {
				throw new IllegalArgumentException("No value for {{" + match.group(1) + "}} outside a section");
			}
			return Matcher.quoteReplacement(value);
		});
		return rendered.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip();
	}

}
