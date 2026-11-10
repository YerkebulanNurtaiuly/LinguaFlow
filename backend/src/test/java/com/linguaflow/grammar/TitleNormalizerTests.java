package com.linguaflow.grammar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class TitleNormalizerTests {

	@Test
	void ignoresCaseSpacingArticleAndSharpS() {
		assertThat(TitleNormalizer.normalize("de", "  Der   Genitiv ")).isEqualTo("genitiv");
		assertThat(TitleNormalizer.normalize("de", "Großschreibung")).isEqualTo("grossschreibung");
		assertThat(TitleNormalizer.normalize("de", "Nebensätze mit weil")).isEqualTo("nebensätze mit weil");
	}

	/** The dev seed's normalized titles come from the Python generator and must match. */
	@Test
	void agreesWithTheNormalizedTitlesInTheDevSeed() throws IOException {
		String seed;
		try (InputStream in = getClass().getResourceAsStream("/db/dev/R__dev_seed.sql")) {
			assertThat(in).as("dev seed on the classpath").isNotNull();
			seed = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		int start = seed.indexOf("insert into grammar_topics");
		assertThat(start).as("grammar insert in the seed").isNotNegative();
		// Descriptions may contain ";", so the statement ends at its ON CONFLICT clause
		String insert = seed.substring(start, seed.indexOf("on conflict", start));

		Matcher row = Pattern.compile("\\('[0-9a-f-]{36}', '[0-9a-f-]{36}', '(\\w+)', '((?:[^']|'')*)', '((?:[^']|'')*)'")
			.matcher(insert);
		List<String> mismatches = new ArrayList<>();
		int rows = 0;
		while (row.find()) {
			rows++;
			String title = row.group(2).replace("''", "'");
			String stored = row.group(3).replace("''", "'");
			String computed = TitleNormalizer.normalize(row.group(1), title);
			if (!computed.equals(stored)) {
				mismatches.add(title + ": seed has \"" + stored + "\", app computes \"" + computed + "\"");
			}
		}
		assertThat(rows).as("grammar rows found in the seed").isGreaterThanOrEqualTo(10);
		assertThat(mismatches).isEmpty();
	}

}
