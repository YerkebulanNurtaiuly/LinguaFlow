package com.linguaflow.ai.prompt;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * Values are inserted as they are; prompt safety (delimiting, limits) is AI-07.
 */
public final class PromptVariables {

	private final Map<PromptVar, String> values = new EnumMap<>(PromptVar.class);

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
		String text = render(value);
		if (text.isEmpty()) {
			values.remove(variable);
		}
		else {
			values.put(variable, text);
		}
		return this;
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

	private static String render(@Nullable Object value) {
		return switch (value) {
			case null -> "";
			case CharSequence text -> text.toString().strip();
			case Number number -> number.toString();
			case Enum<?> constant -> constant.name();
			case Collection<?> items -> items.stream()
				.map(PromptVariables::render)
				.filter(item -> !item.isEmpty())
				.map(item -> "- " + item.replace("\n", " "))
				.collect(Collectors.joining("\n"));
			default -> throw new IllegalArgumentException(
					"Unsupported prompt value type: " + value.getClass().getName());
		};
	}

}
