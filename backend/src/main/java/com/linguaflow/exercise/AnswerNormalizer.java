package com.linguaflow.exercise;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Brings an answer and a solution into comparable form (EX-03). There are two levels
 * and an umlaut folding; a match only at a looser level is still correct, and
 * the evaluation says what differed.
 * <ol>
 * <li>{@link #exact}: NFC, typographic quotes and dashes made plain, whitespace collapsed,
 * and punctuation removed (it never changes a German answer's grammar here). Case is
 * kept.</li>
 * <li>{@link #ignoringCase}: also lower case.</li>
 * <li>{@link #foldUmlauts}, on either: ä, ö, ü and ß spelled ae, oe, ue and ss, for
 * learners without a German keyboard. It keeps case, so it combines with either level.</li>
 * </ol>
 * Hyphens inside words ("E-Mail") and apostrophes ("geht's") are kept.
 */
public final class AnswerNormalizer {

	private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

	/** Sentence punctuation; hyphens and apostrophes inside words stay. */
	private static final Pattern PUNCTUATION = Pattern.compile("[.,;:!?¿¡\"„“”‚‘«»‹›…()\\[\\]{}]");

	/** A hyphen or dash standing alone, e.g. "Ja - gern". */
	private static final Pattern LOOSE_DASH = Pattern.compile("(?<=\\s|^)[-–—]+(?=\\s|$)");

	private AnswerNormalizer() {
	}

	public static String exact(@Nullable String text) {
		if (text == null) {
			return "";
		}
		String plain = Normalizer.normalize(text, Normalizer.Form.NFC)
			.replace('’', '\'')
			.replace('`', '\'')
			.replace('´', '\'');
		plain = PUNCTUATION.matcher(plain).replaceAll(" ");
		plain = LOOSE_DASH.matcher(plain).replaceAll(" ");
		return WHITESPACE.matcher(plain.strip()).replaceAll(" ");
	}

	public static String ignoringCase(@Nullable String text) {
		return exact(text).toLowerCase(Locale.GERMAN);
	}

	public static String foldUmlauts(String text) {
		return text.replace("ä", "ae")
			.replace("ö", "oe")
			.replace("ü", "ue")
			.replace("Ä", "Ae")
			.replace("Ö", "Oe")
			.replace("Ü", "Ue")
			.replace("ß", "ss")
			.replace("ẞ", "SS");
	}

}
