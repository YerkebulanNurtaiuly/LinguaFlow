package com.linguaflow.exercise;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.linguaflow.common.domain.ExerciseType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wrong answers as the learner can read them again (PRG-04): the exercise as they saw
 * it, what they answered, and the right answer, written like the mistake explanations
 * (EX-06) write them for the AI.
 */
@Service
public class MistakeExamples {

	/** One attempt, described. */
	public record Example(UUID exerciseId, ExerciseType type, String exercise, String answer, String correctAnswer) {
	}

	private final ExerciseAttemptRepository attempts;

	private final ExerciseRepository exercises;

	MistakeExamples(ExerciseAttemptRepository attempts, ExerciseRepository exercises) {
		this.attempts = attempts;
		this.exercises = exercises;
	}

	/** The user's attempts among {@code attemptIds}, by attempt ID; others are left out. */
	@Transactional(readOnly = true)
	public Map<UUID, Example> describe(UUID userId, Collection<UUID> attemptIds) {
		Map<UUID, Example> examples = new HashMap<>();
		if (attemptIds.isEmpty()) {
			return examples;
		}
		List<ExerciseAttempt> found = attempts.findAllByUserIdAndIdIn(userId, attemptIds);
		Map<UUID, Exercise> byId = exercises
			.findAllByUserIdAndIdIn(userId, found.stream().map(ExerciseAttempt::getExerciseId).distinct().toList())
			.stream()
			.collect(Collectors.toMap(Exercise::getId, Function.identity()));
		for (ExerciseAttempt attempt : found) {
			Exercise exercise = byId.get(attempt.getExerciseId());
			if (exercise == null) {
				continue;
			}
			ExerciseContent content = exercise.content();
			ExerciseAnswer answer = ExerciseContent.fromJson(attempt.getAnswer(),
					ExerciseAnswer.typeFor(exercise.getType()));
			examples.put(attempt.getId(),
					new Example(exercise.getId(), exercise.getType(), MistakeExplainer.exerciseText(content.prompt()),
							MistakeExplainer.answerText(content, answer),
							AnswerEvaluator.evaluate(content, answer, AnswerEvaluator.Options.DEFAULT).correctAnswer()));
		}
		return examples;
	}

}
