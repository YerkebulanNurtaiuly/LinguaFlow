package com.linguaflow.vocabulary;

import java.util.UUID;

/**
 * Published after a word was added, by hand, in bulk or by import. Other modules react
 * without the vocabulary module knowing them, e.g. the active study session attaches it
 * (SES-01).
 */
public record VocabularyItemCreated(UUID userId, String languageCode, UUID vocabularyItemId) {
}
