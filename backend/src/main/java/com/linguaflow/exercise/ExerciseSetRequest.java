package com.linguaflow.exercise;

import java.util.List;
import java.util.UUID;

import com.linguaflow.common.domain.ExerciseType;
import org.jspecify.annotations.Nullable;

/**
 * What the learner asked for when a set was generated (EX-02), stored as JSON in
 * {@code exercise_sets.request} so the set can be explained and regenerated.
 * @param types empty means any type
 * @param difficulty null means the generator chooses
 * @param vocabularyItemIds words to practise first (a practice session's selection,
 * PRC-02); empty means the generator picks the learner's words still being learned
 */
public record ExerciseSetRequest(List<UUID> grammarTopicIds, List<ExerciseType> types, int count,
		@Nullable ExerciseDifficulty difficulty, List<UUID> vocabularyItemIds) {

	public ExerciseSetRequest {
		grammarTopicIds = grammarTopicIds == null ? List.of() : List.copyOf(grammarTopicIds);
		types = types == null ? List.of() : List.copyOf(types);
		vocabularyItemIds = vocabularyItemIds == null ? List.of() : List.copyOf(vocabularyItemIds);
		if (count < 1) {
			throw new IllegalArgumentException("count must be positive");
		}
	}

	public ExerciseSetRequest(List<UUID> grammarTopicIds, List<ExerciseType> types, int count,
			@Nullable ExerciseDifficulty difficulty) {
		this(grammarTopicIds, types, count, difficulty, List.of());
	}

}
