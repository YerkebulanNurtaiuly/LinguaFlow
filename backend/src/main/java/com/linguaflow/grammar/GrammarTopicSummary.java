package com.linguaflow.grammar;

import java.util.UUID;

import com.linguaflow.common.domain.CefrLevel;
import org.jspecify.annotations.Nullable;

/**
 * What other modules need to know about a topic for an AI prompt, such as exercise
 * generation (EX-02).
 * @param normalizedTitle {@link TitleNormalizer}'s key, for matching an AI's echo back
 */
public record GrammarTopicSummary(UUID id, String title, String normalizedTitle, @Nullable String description,
		@Nullable CefrLevel cefrLevel) {
}
