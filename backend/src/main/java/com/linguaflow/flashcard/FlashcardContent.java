package com.linguaflow.flashcard;

import java.util.List;
import java.util.Locale;

import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.vocabulary.PartOfSpeech;
import com.linguaflow.vocabulary.VocabularyItem;
import org.jspecify.annotations.Nullable;

/**
 * What a flashcard shows (FC-01), built from its word every time it's read, never stored
 * (schema.md Q3). The same word always gives the same content, so editing the word is
 * all it takes to change its cards.
 * <ul>
 * <li>{@code TARGET_TO_NATIVE}: the word on the front ("der Hund"), its meaning on the
 * back.</li>
 * <li>{@code NATIVE_TO_TARGET}: the meaning on the front, with the part of speech as a hint
 * ("noun", so the learner knows to give the article), and the word on the back.</li>
 * </ul>
 * Both carry the plural ("die Hunde"), a verb's principal parts ("lief, ist gelaufen") and
 * up to three examples, for the back.
 * @param front what's asked
 * @param hint shown with the front, e.g. "noun"; null when there's nothing to add
 * @param back the answer
 * @param complete false when the word has no translation yet, e.g. while the AI is filling
 * it in again; the review queue (SRS-02) skips such cards
 */
public record FlashcardContent(CardDirection direction, String front, @Nullable String hint, String back,
		@Nullable String plural, @Nullable VerbForms verbForms, List<Example> examples, boolean complete) {

	/** How many examples a card shows. */
	static final int MAX_EXAMPLES = 3;

	/** Shown in place of a missing translation. */
	static final String NO_TRANSLATION = "…";

	/** e.g. praeteritum "lief", perfect "ist gelaufen". */
	public record VerbForms(String praeteritum, String perfect) {
	}

	public record Example(String sentence, @Nullable String translation) {
	}

	private static final List<String> GERMAN_ARTICLES = List.of("der ", "die ", "das ");

	public static FlashcardContent of(CardDirection direction, VocabularyItem word) {
		String target = wordWithArticle(word);
		String meaning = word.getTranslation();
		boolean complete = meaning != null && !meaning.isBlank();
		String shownMeaning = complete ? meaning : NO_TRANSLATION;
		List<Example> examples = word.getExamples()
			.stream()
			.limit(MAX_EXAMPLES)
			.map(example -> new Example(example.getSentence(), example.getTranslation()))
			.toList();
		return switch (direction) {
			case TARGET_TO_NATIVE -> new FlashcardContent(direction, target, null, shownMeaning, plural(word),
					verbForms(word), examples, complete);
			case NATIVE_TO_TARGET -> new FlashcardContent(direction, shownMeaning, hint(word.getPartOfSpeech()), target,
					plural(word), verbForms(word), examples, complete);
		};
	}

	/** "der Hund" for a German noun, however the learner typed it ("Hund" or "der Hund"). */
	static String wordWithArticle(VocabularyItem word) {
		String article = word.article();
		if (article == null || word.getPartOfSpeech() != PartOfSpeech.NOUN) {
			return word.getLemma();
		}
		return article + " " + withoutArticle(word.getLemma());
	}

	/** "die Hunde"; nouns only, and only when there's a plural. */
	static @Nullable String plural(VocabularyItem word) {
		String plural = word.getPlural();
		if (plural == null || plural.isBlank() || word.getPartOfSpeech() == PartOfSpeech.VERB) {
			return null;
		}
		// German plurals all take "die"
		return "de".equals(word.getLanguageCode()) ? "die " + withoutArticle(plural.strip()) : plural.strip();
	}

	static @Nullable VerbForms verbForms(VocabularyItem word) {
		if (word.getPartOfSpeech() != PartOfSpeech.VERB || !word.hasVerbForms()) {
			return null;
		}
		Object praeteritum = word.getExtra().get("praeteritum");
		Object partizip2 = word.getExtra().get("partizip2");
		// The AI and the edit form write SEIN/HABEN; older data (the dev seed) writes sein/haben
		String auxiliary = "SEIN".equalsIgnoreCase(String.valueOf(word.getExtra().get("auxiliary"))) ? "ist" : "hat";
		return new VerbForms(String.valueOf(praeteritum), auxiliary + " " + partizip2);
	}

	private static @Nullable String hint(@Nullable PartOfSpeech partOfSpeech) {
		return partOfSpeech == null || partOfSpeech == PartOfSpeech.OTHER ? null
				: partOfSpeech.name().toLowerCase(Locale.ROOT);
	}

	private static String withoutArticle(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		for (String article : GERMAN_ARTICLES) {
			if (lower.startsWith(article)) {
				return text.substring(article.length()).strip();
			}
		}
		return text;
	}

}
