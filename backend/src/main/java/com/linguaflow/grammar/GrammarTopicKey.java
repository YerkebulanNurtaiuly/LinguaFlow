package com.linguaflow.grammar;

import java.util.UUID;

/** A user's topic as the catalog sees it: picked from an entry, or matching one by title. */
public record GrammarTopicKey(UUID id, UUID catalogTopicId, String normalizedTitle) {
}
