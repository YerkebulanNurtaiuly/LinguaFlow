package com.linguaflow.vocabulary;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns a lemma as the user typed it into the key used for duplicate detection
 * ({@code vocabulary_items.normalized_lemma}). "der Hund", " Der  HUND " and "Hund" all
 * become "hund"; "Straße" and "Strasse" both become "strasse".
 * <p>
 * Steps, in order:
 * <ol>
 * <li>collapse every run of Unicode whitespace (including no-break spaces) to one space
 * and trim;</li>
 * <li>lower-case (locale-independent);</li>
 * <li>Unicode NFC, so "ü" typed as u + combining diaeresis equals the precomposed "ü";</li>
 * <li>for languages with articles, remove ONE leading definite article followed by a
 * space (German: der, die, das). A lone article stays as it is;</li>
 * <li>"ß" becomes "ss" (ẞ is lower-cased to ß first), matching Swiss spelling and the
 * way users without the key type it.</li>
 * </ol>
 * <b>Keep in sync with {@code norm()} in {@code backend/tools/generate_dev_seed.py}</b>:
 * the dev seed stores normalized lemmas, and {@code LemmaNormalizerTests} checks every
 * seeded word against this class.
 */
public final class LemmaNormalizer {

	private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

	/** Definite articles stripped per target language (ISO 639-1). */
	private static final Map<String, List<String>> ARTICLES = Map.of("de", List.of("der", "die", "das"));

	private LemmaNormalizer() {
	}

	public static String normalize(String languageCode, String lemma) {
		String text = WHITESPACE.matcher(lemma).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
		text = Normalizer.normalize(text, Normalizer.Form.NFC);
		for (String article : ARTICLES.getOrDefault(languageCode, List.of())) {
			String prefix = article + " ";
			if (text.startsWith(prefix)) {
				text = text.substring(prefix.length());
				break;
			}
		}
		return text.replace("ß", "ss");
	}

}
