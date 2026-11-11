package com.linguaflow.flashcard;

import com.linguaflow.common.persistence.OwnedRepository;

/** Spaced-repetition state, one row per flashcard, keyed by the card's ID. */
public interface SrsStateRepository extends OwnedRepository<SrsState> {

}
