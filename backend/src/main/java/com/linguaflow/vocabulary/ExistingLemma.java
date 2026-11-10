package com.linguaflow.vocabulary;

import java.util.UUID;

/** A word the user already has, as seen by the bulk duplicate check. */
public record ExistingLemma(UUID id, String normalizedLemma, PartOfSpeech partOfSpeech) {
}
