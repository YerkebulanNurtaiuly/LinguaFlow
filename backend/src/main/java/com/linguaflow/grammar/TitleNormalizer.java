package com.linguaflow.grammar;

import com.linguaflow.vocabulary.LemmaNormalizer;

/**
 * The duplicate key of a topic title. It is the same normalization as vocabulary lemmas
 * (whitespace collapsed, lower case, NFC, ß as ss, one leading German article dropped), so
 * "Der Genitiv" and "genitiv" are one topic. The dev seed's normalized titles are computed
 * the same way (norm() in generate_dev_seed.py).
 */
public final class TitleNormalizer {

	private TitleNormalizer() {
	}

	public static String normalize(String languageCode, String title) {
		return LemmaNormalizer.normalize(languageCode, title);
	}

}
