package com.linguaflow.grammar;

import java.util.UUID;

/**
 * Published after a grammar topic was added, typed or picked from the catalog. The active
 * study session attaches it (SES-01).
 */
public record GrammarTopicCreated(UUID userId, String languageCode, UUID grammarTopicId) {
}
