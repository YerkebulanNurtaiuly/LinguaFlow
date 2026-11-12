package com.linguaflow.vocabulary;

import java.util.UUID;

import com.linguaflow.common.domain.KnowledgeStatus;

/**
 * One of the learner's enriched words, for other modules' AI prompts, such as exercise
 * generation (EX-02).
 * @param lemma as stored, e.g. "der Hund"
 * @param normalizedLemma {@link LemmaNormalizer}'s key, for matching an AI's echo back
 */
public record KnownWord(UUID id, String lemma, String normalizedLemma, String translation, KnowledgeStatus status) {
}
