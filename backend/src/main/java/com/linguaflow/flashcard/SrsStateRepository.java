package com.linguaflow.flashcard;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;

/** Spaced-repetition state, one row per flashcard, keyed by the card's ID. */
public interface SrsStateRepository extends OwnedRepository<SrsState> {

	/** The states of the user's cards among {@code ids}. */
	List<SrsState> findAllByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

}
