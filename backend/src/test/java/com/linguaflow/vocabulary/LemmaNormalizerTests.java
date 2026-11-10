package com.linguaflow.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Table-driven checks of the duplicate-detection key (VOC-01). */
class LemmaNormalizerTests {

	@ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
	@CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, textBlock = """
			Hund|hund
			der Hund|hund
			Der Hund|hund
			DER HUND|hund
			die Katze|katze
			das Haus|haus
			'  der   Hund  '|hund
			der\tHund|hund
			der Hund|hund
			die Straße|strasse
			Strasse|strasse
			STRASSE|strasse
			STRAẞE|strasse
			Fuß|fuss
			Müller|müller
			Müller|müller
			die|die
			der|der
			'das '|das
			Derweil|derweil
			Dieb|dieb
			der die Hund|die hund
			das ist gut|ist gut
			sich freuen|sich freuen
			'guten   Morgen'|guten morgen
			""")
	void normalizesGermanLemmas(String lemma, String expected) {
		assertThat(LemmaNormalizer.normalize("de", lemma)).isEqualTo(expected);
	}

	@Test
	void stripsArticlesOnlyForLanguagesThatHaveThem() {
		assertThat(LemmaNormalizer.normalize("en", "der Hund")).isEqualTo("der hund");
		assertThat(LemmaNormalizer.normalize("en", "  The   Dog ")).isEqualTo("the dog");
	}

	@Test
	void treatsDecomposedAndPrecomposedUmlautsAsTheSameWord() {
		String precomposed = "die Mäuse";
		String decomposed = "die Mäuse";
		assertThat(precomposed).isNotEqualTo(decomposed);
		assertThat(LemmaNormalizer.normalize("de", decomposed)).isEqualTo(LemmaNormalizer.normalize("de", precomposed));
	}

	/**
	 * The dev seed stores normalized lemmas computed by the Python generator; they must be
	 * exactly what the app computes, or seeded words wouldn't be found as duplicates.
	 */
	@Test
	void agreesWithTheNormalizedLemmasInTheDevSeed() throws IOException {
		String seed;
		try (InputStream in = getClass().getResourceAsStream("/db/dev/R__dev_seed.sql")) {
			assertThat(in).as("dev seed on the classpath").isNotNull();
			seed = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		int start = seed.indexOf("insert into vocabulary_items");
		assertThat(start).as("vocabulary insert in the seed").isNotNegative();
		String vocabularyInsert = seed.substring(start, seed.indexOf(';', start));
		// ('<uuid>', '<uuid>', 'de', '<lemma>', '<normalized>', ...
		Matcher row = Pattern.compile("\\('[0-9a-f-]{36}', '[0-9a-f-]{36}', '(\\w+)', '((?:[^']|'')*)', '((?:[^']|'')*)'")
			.matcher(vocabularyInsert);
		List<String> mismatches = new ArrayList<>();
		int rows = 0;
		while (row.find()) {
			rows++;
			String lemma = row.group(2).replace("''", "'");
			String stored = row.group(3).replace("''", "'");
			String computed = LemmaNormalizer.normalize(row.group(1), lemma);
			if (!computed.equals(stored)) {
				mismatches.add(lemma + ": seed has \"" + stored + "\", app computes \"" + computed + "\"");
			}
		}
		assertThat(rows).as("vocabulary rows found in the seed").isGreaterThanOrEqualTo(50);
		assertThat(mismatches).isEmpty();
	}

}
