package com.linguaflow.ai.prompt;

import java.text.Normalizer;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Text clean-up for prompt safety (AI-07), used on learner input before it enters a
 * prompt and on string values in model answers.
 * <ul>
 * <li>{@link #removeInvisible}: drops control characters (keeping tab and newline),
 * zero-width and bidirectional-override characters, and Unicode tag characters, which
 * can smuggle instructions a person can't see.</li>
 * <li>{@link #redactPersonalData}: replaces email addresses, phone numbers, IBANs and
 * long digit runs, so they don't leave for the provider (SEC-02) or reach the shared
 * cache.</li>
 * <li>{@link #looksLikeInjection}: a heuristic for text that tries to instruct the
 * model. It only flags; the text stays delimited data.</li>
 * </ul>
 */
public final class TextSafety {

	private TextSafety() {
	}

	/** C0/C1 controls except tab and newline, zero-width, bidi controls, BOM and tag characters. */
	private static final Pattern INVISIBLE = Pattern.compile(
			"[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F-\\x9F\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069"
					+ "\\uFEFF\\uFFF9-\\uFFFB]|[\\x{E0000}-\\x{E007F}]");

	private static final Pattern EMAIL = Pattern.compile("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.\\p{L}{2,}");

	private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?\\b");

	/** Eight or more digits, possibly grouped with spaces, dashes, dots, slashes or brackets. */
	private static final Pattern PHONE_OR_NUMBER = Pattern.compile("\\+?\\d(?:[\\s./()-]{0,2}\\d){7,}");

	private static final List<Pattern> INJECTION = List.of(
			Pattern.compile("(?iu)\\b(ignore|disregard|forget|override)\\b.{0,30}\\b(previous|prior|above|earlier|all|your|the)\\b.{0,20}\\b(instructions?|rules?|prompts?|directions?)\\b"),
			Pattern.compile("(?iu)\\b(ignoriere|vergiss|missachte)\\b.{0,30}\\b(anweisungen|regeln|vorgaben|instruktionen)\\b"),
			Pattern.compile("(?iu)\\b(reveal|show|print|repeat)\\b.{0,20}\\b(system prompt|your (prompt|instructions))\\b"),
			Pattern.compile("(?iu)\\byou are now\\b|\\bact as (an? )?(ai|assistant|system)\\b|\\bnew instructions\\b"),
			Pattern.compile("(?iu)(^|\\n)\\s*(system|assistant|human|user)\\s*:"),
			Pattern.compile("(?iu)</?\\s*(system|assistant|instructions?|prompt)\\s*>"));

	/** Removes invisible and control characters; tab and newline stay. */
	public static String removeInvisible(String text) {
		return INVISIBLE.matcher(text).replaceAll("");
	}

	/** NFC, Windows/old-Mac line breaks as {@code \n}, invisible characters removed. */
	static String normalize(String text, Set<PromptInputFlag> flags) {
		String normalized = Normalizer.normalize(text, Normalizer.Form.NFC).replace("\r\n", "\n").replace('\r', '\n');
		String cleaned = removeInvisible(normalized);
		if (!cleaned.equals(normalized)) {
			flags.add(PromptInputFlag.CONTROL_CHARACTERS_REMOVED);
		}
		return cleaned;
	}

	/** Replaces personal data with placeholders such as {@code [email]}. */
	static String redactPersonalData(String text, Set<PromptInputFlag> flags) {
		String redacted = EMAIL.matcher(text).replaceAll("[email]");
		redacted = IBAN.matcher(redacted).replaceAll("[iban]");
		redacted = PHONE_OR_NUMBER.matcher(redacted).replaceAll("[number]");
		if (!redacted.equals(text)) {
			flags.add(PromptInputFlag.PERSONAL_DATA_REDACTED);
		}
		return redacted;
	}

	/** {@code <} and {@code >} as entities, so the text can't open or close a delimiting tag. */
	static String escapeMarkup(String text, Set<PromptInputFlag> flags) {
		if (text.indexOf('<') < 0 && text.indexOf('>') < 0) {
			return text;
		}
		flags.add(PromptInputFlag.MARKUP_ESCAPED);
		return text.replace("<", "&lt;").replace(">", "&gt;");
	}

	public static boolean looksLikeInjection(String text) {
		return INJECTION.stream().anyMatch(pattern -> pattern.matcher(text).find());
	}

	/** Cuts to {@code max} code points, ending with "…". */
	static String limit(String text, int max, Set<PromptInputFlag> flags) {
		if (text.codePointCount(0, text.length()) <= max) {
			return text;
		}
		flags.add(PromptInputFlag.TRUNCATED);
		int end = text.offsetByCodePoints(0, Math.max(0, max - 1));
		return text.substring(0, end).stripTrailing() + "…";
	}

}
