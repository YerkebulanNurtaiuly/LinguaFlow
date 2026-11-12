package com.linguaflow.exercise;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.linguaflow.ai.AiErrors;
import com.linguaflow.ai.LlmException;
import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.common.error.InvalidFieldException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Answering an exercise (EX-05): the answer is checked ({@link AnswerChecker}), then the
 * attempt, its mistakes and an {@link ExerciseAttemptedEvent} go into one transaction.
 * <ul>
 * <li><b>Mistakes:</b> one per AI judge error, with its type, span, explanation and
 * topic; for a closed format, one per wrong gap ({@code WRONG_GAP}, with what was typed),
 * one for a wrong option ({@code WRONG_CHOICE}, with the option chosen) or one for a
 * wrong order ({@code WORD_ORDER}, with the order given). Mistakes from the rules belong
 * to the exercise's topic when it has exactly one.</li>
 * <li><b>AI failures</b> store nothing: the quota is a 429 {@code AI_QUOTA_EXCEEDED}, any
 * other failure a 503 {@code AI_UNAVAILABLE}, so the learner can try again.</li>
 * </ul>
 */
@Service
public class AttemptService {

	private final AnswerChecker checker;

	private final ExerciseRepository exercises;

	private final ExerciseSetRepository sets;

	private final ExerciseAttemptRepository attempts;

	private final MistakeRepository mistakes;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	private final TransactionTemplate tx;

	AttemptService(AnswerChecker checker, ExerciseRepository exercises, ExerciseSetRepository sets,
			ExerciseAttemptRepository attempts, MistakeRepository mistakes, ApplicationEventPublisher events, Clock clock,
			PlatformTransactionManager transactions) {
		this.checker = checker;
		this.exercises = exercises;
		this.sets = sets;
		this.attempts = attempts;
		this.mistakes = mistakes;
		this.events = events;
		this.clock = clock;
		this.tx = new TransactionTemplate(transactions);
	}

	/** A stored attempt with how it was graded and what the learner should see. */
	public record Result(ExerciseAttempt attempt, Evaluation evaluation, ExerciseContent content,
			List<Mistake> mistakes) {
	}

	/**
	 * @throws InvalidFieldException on {@code answer} when it isn't the shape the exercise
	 * type takes
	 */
	public Result submit(UUID userId, UUID exerciseId, ExerciseAnswer answer, @Nullable Integer durationMs) {
		Evaluation evaluation;
		try {
			evaluation = checker.check(userId, exerciseId, answer);
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidFieldException("answer", "doesn't fit this exercise: " + ex.getMessage());
		}
		catch (LlmException ex) {
			throw AiErrors.toApiException(ex, clock);
		}
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		return Objects.requireNonNull(tx.execute(status -> {
			Exercise exercise = exercises.requireOwned(exerciseId, userId);
			ExerciseSet set = sets.requireOwned(exercise.getExerciseSetId(), userId);
			ExerciseContent content = exercise.content();
			ExerciseAttempt attempt = attempts.saveAndFlush(new ExerciseAttempt(exercise, answer, evaluation, now,
					durationMs));
			List<Mistake> found = new ArrayList<>();
			for (Mistake mistake : mistakesOf(attempt, exercise, content, answer, evaluation, now)) {
				found.add(mistakes.save(mistake));
			}
			events.publishEvent(new ExerciseAttemptedEvent(userId, set.getLanguageCode(), exercise.getId(),
					attempt.getId(), exercise.getType(), evaluation.correct(), evaluation.score(),
					exercise.getGrammarTopicIds(), exercise.getVocabularyItemIds(), now));
			return new Result(attempt, evaluation, content, found);
		}));
	}

	static List<Mistake> mistakesOf(ExerciseAttempt attempt, Exercise exercise, ExerciseContent content,
			ExerciseAnswer answer, Evaluation evaluation, Instant now) {
		List<Mistake> found = new ArrayList<>();
		if (evaluation.feedback() != null) {
			for (Evaluation.AnswerError error : evaluation.feedback().errors()) {
				found.add(new Mistake(attempt, error.type().name(), error.grammarTopicId(), null, error.span(),
						error.explanation(), now));
			}
			return found;
		}
		if (evaluation.correct()) {
			return found;
		}
		Set<UUID> topics = exercise.getGrammarTopicIds();
		UUID topic = topics.size() == 1 ? topics.iterator().next() : null;
		switch (answer) {
			case ExerciseAnswer.Blanks blanks -> {
				for (int i = 0; i < evaluation.parts().size(); i++) {
					if (!evaluation.parts().get(i)) {
						String typed = i < blanks.blanks().size() ? blanks.blanks().get(i) : null;
						found.add(new Mistake(attempt, Mistake.WRONG_GAP, topic, null, typed, null, now));
					}
				}
			}
			case ExerciseAnswer.Choice choice -> {
				List<String> options = ((ExercisePrompt.MultipleChoice) content.prompt()).options();
				String chosen = choice.index() != null && choice.index() >= 0 && choice.index() < options.size()
						? options.get(choice.index()) : null;
				found.add(new Mistake(attempt, Mistake.WRONG_CHOICE, topic, null, chosen, null, now));
			}
			case ExerciseAnswer.Tokens tokens -> found.add(new Mistake(attempt, ErrorType.WORD_ORDER.name(), topic,
					null, String.join(" ", tokens.tokens().stream().map(String::valueOf).toList()), null, now));
			case ExerciseAnswer.Text text -> found.add(new Mistake(attempt, ErrorType.OTHER.name(), topic, null,
					text.text(), null, now));
		}
		return found;
	}

	/** The type of the user's exercise, which decides the answer's shape; 404 otherwise. */
	@Transactional(readOnly = true)
	public ExerciseType typeOf(UUID userId, UUID exerciseId) {
		return exercises.requireOwned(exerciseId, userId).getType();
	}

	/** One exercise's attempts; the exercise must be the user's. */
	@Transactional(readOnly = true)
	public Page<ExerciseAttempt> history(UUID userId, UUID exerciseId, Pageable pageable) {
		exercises.requireOwned(exerciseId, userId);
		return attempts.findAllByUserIdAndExerciseId(userId, exerciseId, pageable);
	}

}
