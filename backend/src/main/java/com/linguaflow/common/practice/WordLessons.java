package com.linguaflow.common.practice;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Which lesson (study session) each word was met in, for combined exercises (PRC-03).
 * The learning module implements it, so the exercise module needn't depend on it.
 */
public interface WordLessons {

	/**
	 * The latest lesson of each of the user's words that's in one, as a label such as
	 * "Lektion 5 (3 Nov)"; words in no lesson are left out.
	 */
	Map<UUID, String> latestLessons(UUID userId, Collection<UUID> vocabularyItemIds);

}
