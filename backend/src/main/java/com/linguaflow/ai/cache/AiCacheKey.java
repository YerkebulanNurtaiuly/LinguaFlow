package com.linguaflow.ai.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import com.linguaflow.ai.AiUseCase;
import org.jspecify.annotations.Nullable;

/**
 * What a cached AI result was made from (AI-06). Two calls with equal keys must be able
 * to share a result, so a key may only consist of language data: the use case, the
 * languages, the prompt version and normalized input such as a lemma. Never put a user
 * ID, a learner's own text or anything personal into it (schema.md Q5).
 * <p>
 * The prompt version is part of the key, so a new template version starts a fresh cache.
 * The stored key is the SHA-256 of all parts.
 * @param languageCode the learning language; {@code ai_cache.language_code}
 * @param parts the normalized input; null parts count as empty
 */
public record AiCacheKey(AiUseCase useCase, String languageCode, String promptVersion, List<String> parts) {

	private static final char SEPARATOR = '\u001f';

	public AiCacheKey {
		Objects.requireNonNull(useCase, "useCase");
		Objects.requireNonNull(languageCode, "languageCode");
		Objects.requireNonNull(promptVersion, "promptVersion");
		parts = List.copyOf(parts);
	}

	public static AiCacheKey of(AiUseCase useCase, String languageCode, String promptVersion,
			@Nullable String... parts) {
		return new AiCacheKey(useCase, languageCode, promptVersion,
				Arrays.stream(parts).map(part -> part == null ? "" : part).toList());
	}

	/**
	 * A word's enrichment (VOC-05), by (language, normalized lemma, part of speech) and the
	 * explanation language its translation and notes are written in.
	 * @param normalizedLemma {@code LemmaNormalizer}'s output, so "der Hund" and "Hund"
	 * share an entry
	 * @param partOfSpeech null when the learner didn't choose one
	 */
	public static AiCacheKey vocabularyEnrichment(String languageCode, String explanationLanguageCode,
			String promptVersion, String normalizedLemma, @Nullable String partOfSpeech) {
		return of(AiUseCase.VOCABULARY_ENRICHMENT, languageCode, promptVersion, explanationLanguageCode,
				normalizedLemma, partOfSpeech);
	}

	/** The {@code ai_cache.cache_key}: 64 hex characters. */
	public String hash() {
		StringBuilder text = new StringBuilder(useCase.name()).append(SEPARATOR)
			.append(languageCode)
			.append(SEPARATOR)
			.append(promptVersion);
		parts.forEach(part -> text.append(SEPARATOR).append(part));
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
