package com.linguaflow.ai.prompt;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/**
 * The values for one rendering of a template (AI-03). Start with
 * {@link #forLanguages(String, String)}, then {@link #set} the rest.
 * <ul>
 * <li>Strings are stripped, numbers and enums written as they print
 * ({@code CefrLevel.A2} → "A2").</li>
 * <li>A collection becomes one {@code - item} line per non-blank element, e.g. known
 * vocabulary.</li>
 * <li>{@code null}, blank strings and empty collections count as not set, so a
 * {@code {{#name}}…{{/name}}} section is left out.</li>
 * </ul>
 * Every value except the language names is learner data and is made safe (AI-07), in
 * this order: Unicode NFC and invisible or control characters removed; personal data
 * redacted; reshaped by its {@link PromptVar.Shape}; cut to the variable's limits;
 * {@code <} and {@code >} escaped, so it can't close the tag that delimits it. Text that
 * reads like instructions to the model is flagged. {@link #flags()} says what happened.
 */
public final class PromptVariables {

	private final Map<PromptVar, String> values = new EnumMap<>(PromptVar.class);

	private final Set<PromptInputFlag> flags = EnumSet.noneOf(PromptInputFlag.class);

	private PromptVariables() {
	}

	/**
	 * @param targetCode the learning language's code, e.g. {@code de}
	 * @param explanationCode the explanation language's code, e.g. {@code en}
	 */
	public static PromptVariables forLanguages(String targetCode, String explanationCode) {
		return new PromptVariables().set(PromptVar.TARGET_LANGUAGE, languageName(targetCode))
			.set(PromptVar.EXPLANATION_LANGUAGE, languageName(explanationCode));
	}

	/** Sets {@code variable}; a null or empty value removes it. */
	public PromptVariables set(PromptVar variable, @Nullable Object value) {
		String text = switch (variable.shape()) {
			case TRUSTED -> scalar(value);
			case LINE, TEXT -> safe(variable, scalar(value));
			case LIST -> list(variable, value);
		};
		if (text.isEmpty()) {
			values.remove(variable);
		}
		else {
			values.put(variable, text);
		}
		return this;
	}

	/** What prompt safety changed or noticed in the values set so far. */
	public Set<PromptInputFlag> flags() {
		return Set.copyOf(flags);
	}

	Map<PromptVar, String> values() {
		return values;
	}

	/** "de" → "German": templates are written in English and name languages that way. */
	static String languageName(String code) {
		String name = Locale.forLanguageTag(Objects.requireNonNull(code, "language code")).getDisplayLanguage(Locale.ENGLISH);
		if (name.isBlank() || name.equalsIgnoreCase(code)) {
			throw new IllegalArgumentException("Unknown language code: " + code);
		}
		return name;
	}

	private String list(PromptVar variable, @Nullable Object value) {
		if (value == null) {
			return "";
		}
		// A single value counts as a one-item list
		Collection<?> items = value instanceof Collection<?> many ? many : List.of(value);
		List<String> kept = items.stream()
			.map(PromptVariables::scalar)
			.map(item -> safe(variable, item))
			.filter(item -> !item.isEmpty())
			.toList();
		if (kept.size() > variable.maxItems()) {
			flags.add(PromptInputFlag.TRUNCATED);
			kept = kept.subList(0, variable.maxItems());
		}
		return kept.stream().map(item -> "- " + item).collect(Collectors.joining("\n"));
	}

	/** The AI-07 pipeline for one learner value (or one list item). */
	private String safe(PromptVar variable, String raw) {
		String text = TextSafety.normalize(raw, flags);
		text = TextSafety.redactPersonalData(text, flags);
		text = variable.shape() == PromptVar.Shape.TEXT ? tidyText(text) : oneLine(text);
		if (text.isEmpty()) {
			return text;
		}
		if (TextSafety.looksLikeInjection(text)) {
			flags.add(PromptInputFlag.INJECTION_SUSPECTED);
		}
		text = TextSafety.limit(text, variable.maxLength(), flags);
		return TextSafety.escapeMarkup(text, flags);
	}

	private static String oneLine(String text) {
		return text.replaceAll("\\s+", " ").strip();
	}

	/** Trailing spaces removed, at most one blank line in a row. */
	private static String tidyText(String text) {
		return text.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip();
	}

	private static String scalar(@Nullable Object value) {
		return switch (value) {
			case null -> "";
			case CharSequence text -> text.toString().strip();
			case Number number -> number.toString();
			case Enum<?> constant -> constant.name();
			default -> throw new IllegalArgumentException(
					"Unsupported prompt value type: " + value.getClass().getName());
		};
	}

}
