package com.linguaflow.vocabulary;

import java.util.UUID;

/**
 * Published after AI enrichment made a word READY and that was committed (VOC-05). Other
 * modules react without the vocabulary module knowing them, e.g. flashcards are created
 * for it (FC-01). Also published when a word is enriched again (VOC-06).
 */
public record VocabularyItemReady(UUID userId, String languageCode, UUID vocabularyItemId) {
}
