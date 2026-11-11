package com.linguaflow.ai.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.linguaflow.ai.AiUseCase;
import org.junit.jupiter.api.Test;

class AiCacheKeyTests {

	private static AiCacheKey hund(String explanation, String partOfSpeech) {
		return AiCacheKey.vocabularyEnrichment("de", explanation, "vocabulary-enrichment/v1", "hund", partOfSpeech);
	}

	@Test
	void isAStableSha256OfAllParts() {
		assertThat(hund("en", "NOUN").hash()).hasSize(64)
			.matches("[0-9a-f]+")
			.isEqualTo(hund("en", "NOUN").hash());
	}

	@Test
	void everyPartChangesTheKey() {
		String base = hund("en", "NOUN").hash();
		assertThat(hund("ru", "NOUN").hash()).isNotEqualTo(base);
		assertThat(hund("en", null).hash()).isNotEqualTo(base);
		assertThat(AiCacheKey.vocabularyEnrichment("de", "en", "vocabulary-enrichment/v2", "hund", "NOUN").hash())
			.isNotEqualTo(base);
		assertThat(AiCacheKey.of(AiUseCase.GRAMMAR_EXPLANATION, "de", "vocabulary-enrichment/v1", "en", "hund", "NOUN")
			.hash()).isNotEqualTo(base);
	}

	@Test
	void partsCannotRunIntoEachOther() {
		// "ab" + "c" and "a" + "bc" must not collide
		assertThat(AiCacheKey.of(AiUseCase.VOCABULARY_ENRICHMENT, "de", "v1", "ab", "c").hash())
			.isNotEqualTo(AiCacheKey.of(AiUseCase.VOCABULARY_ENRICHMENT, "de", "v1", "a", "bc").hash());
		// A missing part is the same as an empty one
		assertThat(hund("en", null).hash()).isEqualTo(hund("en", "").hash());
	}

}
