package com.linguaflow.progress;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ErrorCode;
import com.linguaflow.exercise.ExerciseSetService;
import com.linguaflow.progress.FrequentMistakeService.Grouping;
import com.linguaflow.progress.WeakPointService.WeakPoint;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Practise my weak spots" (PRG-06): an exercise set aimed at the learner's weakest
 * grammar topics (PRG-03), with their weakest words as the practice words and their most
 * frequent mistakes on those topics (PRG-04) as points the exercises must test.
 */
@Service
public class WeakSpotsService {

	/** Weak topics one set practises. */
	public static final int TOPICS = 3;

	/** Weak words offered as practice words. */
	public static final int WORDS = 20;

	/** Mistake types per topic. */
	public static final int MISTAKES_PER_TOPIC = 2;

	public static final int DEFAULT_COUNT = 10;

	/** What was chosen, and the set being written. */
	public record Started(UUID exerciseSetId, UUID aiJobId, List<WeakPoint> topics, int words,
			List<String> focusMistakes) {
	}

	private final WeakPointService weakPoints;

	private final FrequentMistakeService mistakes;

	private final ExerciseSetService exerciseSets;

	WeakSpotsService(WeakPointService weakPoints, FrequentMistakeService mistakes, ExerciseSetService exerciseSets) {
		this.weakPoints = weakPoints;
		this.mistakes = mistakes;
		this.exerciseSets = exerciseSets;
	}

	/**
	 * @param count null means {@value #DEFAULT_COUNT}
	 * @throws ApiException 409 {@code NOTHING_TO_PRACTISE} until some grammar topic counts
	 * as weak
	 */
	@Transactional
	public Started start(UUID userId, @Nullable Integer count, List<ExerciseType> types) {
		var found = weakPoints.weakPoints(userId, WORDS);
		List<WeakPoint> topics = found.topics().stream().limit(TOPICS).toList();
		if (topics.isEmpty()) {
			throw new ApiException(ErrorCode.NOTHING_TO_PRACTISE,
					"No weak grammar topics yet: practise some exercises first, so your weak spots can be found.");
		}
		List<String> focus = new ArrayList<>();
		for (WeakPoint topic : topics) {
			var result = mistakes.frequent(userId,
					new FrequentMistakeService.Query(Grouping.TYPE, null, topic.id(), MISTAKES_PER_TOPIC, 1));
			result.groups().forEach(group -> focus.add(describe(topic.name(), group)));
		}
		List<UUID> words = found.words().stream().map(WeakPoint::id).toList();
		var requested = exerciseSets.createForWeakSpots(userId, topics.stream().map(WeakPoint::id).toList(), words,
				focus, types, count == null ? DEFAULT_COUNT : count);
		return new Started(requested.set().getId(), requested.aiJobId(), topics, words.size(), focus);
	}

	/**
	 * One mistake for the prompt, e.g. {@code WRONG_GAP in Dativ (6 times), e.g. answered
	 * "Ich helfe den Mann." where "Ich helfe dem Mann." is right}. The learner's answer is
	 * prompt data; prompt safety (AI-07) cleans and delimits it.
	 */
	static String describe(String topic, FrequentMistakeService.Group group) {
		StringBuilder line = new StringBuilder().append(group.errorType())
			.append(" in ")
			.append(topic)
			.append(" (")
			.append(group.count())
			.append(group.count() == 1 ? " time)" : " times)");
		group.examples()
			.stream()
			.findFirst()
			.ifPresent(example -> {
				if (example.attempt() != null) {
					line.append(", e.g. answered \"")
						.append(example.attempt().answer())
						.append("\" where \"")
						.append(example.attempt().correctAnswer())
						.append("\" is right");
				}
				else if (example.excerpt() != null) {
					line.append(", e.g. \"").append(example.excerpt()).append('"');
				}
			});
		return line.toString();
	}

}
