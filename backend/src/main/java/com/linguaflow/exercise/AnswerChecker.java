package com.linguaflow.exercise;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.linguaflow.ai.AiCaller;
import com.linguaflow.ai.OutputCheck;
import com.linguaflow.ai.OutputProblems;
import com.linguaflow.ai.StructuredLlm;
import com.linguaflow.ai.prompt.PromptLibrary;
import com.linguaflow.ai.prompt.PromptTemplate;
import com.linguaflow.ai.prompt.PromptVar;
import com.linguaflow.ai.prompt.PromptVariables;
import com.linguaflow.ai.prompt.RenderedPrompt;
import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.exercise.Evaluation.AnswerError;
import com.linguaflow.exercise.Evaluation.EvaluatedBy;
import com.linguaflow.exercise.Evaluation.Feedback;
import com.linguaflow.grammar.GrammarTopicService;
import com.linguaflow.grammar.GrammarTopicSummary;
import com.linguaflow.grammar.TitleNormalizer;
import com.linguaflow.user.UserLanguage;
import com.linguaflow.user.UserLanguageService;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks a learner's answer to one of their exercises (EX-03, EX-04): first in code
 * ({@link AnswerEvaluator}), and when that can't decide an open answer, with the AI judge.
 * <ul>
 * <li><b>The judge's prompt</b> has the exercise, its instruction (or the type's default),
 * the reference answer and alternatives, the exercise's topics and up to
 * {@value #LEARNER_TOPICS} of the learner's other topics, and the answer as data.</li>
 * <li><b>Its answer</b> ({@link JudgeAnswer}) must be consistent: correct means score 1
 * and no errors; not correct means a score below 1 and, unless the score is 0, at least
 * one error; every span is copied from the answer. Otherwise StructuredLlm retries once
 * with the problems.</li>
 * <li><b>Topics:</b> each error's topic is matched by normalized title to the exercise's
 * or the learner's topics. A grammar error naming none falls back to the exercise's topic
 * when it has exactly one; spelling, vocabulary and meaning errors don't.</li>
 * <li><b>No cache:</b> the prompt holds the learner's own answer.</li>
 * </ul>
 * The AI call runs while the learner waits and outside any transaction. Provider errors,
 * the quota and an open circuit propagate as {@code LlmException}s; the attempt endpoint
 * (EX-05) turns them into 429 or 503 with {@code AiErrors.toApiException}.
 */
@Service
public class AnswerChecker {

	/** How many of the learner's other topics the judge may map errors to. */
	static final int LEARNER_TOPICS = 30;

	private final ExerciseRepository exercises;

	private final ExerciseSetRepository sets;

	private final AnswerEvaluator evaluator;

	private final GrammarTopicService topics;

	private final UserLanguageService userLanguages;

	private final StructuredLlm llm;

	private final PromptLibrary prompts;

	private final TransactionTemplate tx;

	AnswerChecker(ExerciseRepository exercises, ExerciseSetRepository sets, AnswerEvaluator evaluator,
			GrammarTopicService topics, UserLanguageService userLanguages, StructuredLlm llm, PromptLibrary prompts,
			PlatformTransactionManager transactions) {
		this.exercises = exercises;
		this.sets = sets;
		this.evaluator = evaluator;
		this.topics = topics;
		this.userLanguages = userLanguages;
		this.llm = llm;
		this.prompts = prompts;
		this.tx = new TransactionTemplate(transactions);
		this.tx.setReadOnly(true);
	}

	/** The exercise as the check needs it, read in one short transaction. */
	private record Loaded(String languageCode, ExerciseContent content, Set<UUID> grammarTopicIds) {
	}

	/**
	 * @throws com.linguaflow.common.error.NotFoundException when the exercise isn't the
	 * user's
	 * @throws IllegalArgumentException when the answer isn't the shape the type takes
	 */
	public Evaluation check(UUID userId, UUID exerciseId, ExerciseAnswer answer) {
		Loaded loaded = Objects.requireNonNull(tx.execute(status -> {
			Exercise exercise = exercises.requireOwned(exerciseId, userId);
			ExerciseSet set = sets.requireOwned(exercise.getExerciseSetId(), userId);
			return new Loaded(set.getLanguageCode(), exercise.content(), exercise.getGrammarTopicIds());
		}));
		Evaluation byRules = evaluator.evaluate(loaded.content(), answer);
		if (byRules.decided()) {
			return byRules;
		}
		return judge(userId, loaded, ((ExerciseAnswer.Text) answer).text(), byRules.correctAnswer());
	}

	private Evaluation judge(UUID userId, Loaded loaded, String answer, @Nullable String correctAnswer) {
		String language = loaded.languageCode();
		List<GrammarTopicSummary> exerciseTopics = topics.summaries(userId, language, loaded.grammarTopicIds());
		List<GrammarTopicSummary> otherTopics = topics.recentSummaries(userId, language, LEARNER_TOPICS + 5)
			.stream()
			.filter(topic -> !loaded.grammarTopicIds().contains(topic.id()))
			.limit(LEARNER_TOPICS)
			.toList();
		ExerciseContent content = loaded.content();
		ExerciseSolution.TextAnswer solution = (ExerciseSolution.TextAnswer) content.solution();

		RenderedPrompt prompt = prompts.render(PromptTemplate.ANSWER_EVALUATION,
				PromptVariables.forLanguages(language, userLanguages.explanationLanguageFor(userId, language))
					.set(PromptVar.EXERCISE_TYPE, typeName(content.type()))
					.set(PromptVar.CEFR_LEVEL, level(userId, language, exerciseTopics))
					.set(PromptVar.INSTRUCTION, instruction(content.prompt()))
					.set(PromptVar.EXERCISE, exercise(content.prompt()))
					.set(PromptVar.REFERENCE_ANSWER, solution.answer())
					.set(PromptVar.ACCEPTED_ANSWERS, solution.alternatives())
					.set(PromptVar.FOCUS_TOPICS, exerciseTopics.stream().map(AnswerChecker::describe).toList())
					.set(PromptVar.LEARNER_TOPICS, otherTopics.stream().map(GrammarTopicSummary::title).toList())
					.set(PromptVar.LEARNER_ANSWER, answer));
		JudgeAnswer verdict = llm
			.complete(AiCaller.user(userId), prompt, JudgeAnswer.class, checkFor(answer))
			.value();

		Map<String, UUID> byTitle = new HashMap<>();
		otherTopics.forEach(topic -> byTitle.put(topic.normalizedTitle(), topic.id()));
		exerciseTopics.forEach(topic -> byTitle.put(topic.normalizedTitle(), topic.id()));
		UUID onlyTopic = exerciseTopics.size() == 1 ? exerciseTopics.get(0).id() : null;
		List<AnswerError> errors = new ArrayList<>();
		for (JudgeAnswer.Error error : verdict.errors()) {
			UUID topic = Optional.ofNullable(error.topic())
				.map(title -> title.contains(":") ? title.substring(0, title.indexOf(':')) : title)
				.map(title -> byTitle.get(TitleNormalizer.normalize(language, title)))
				.orElse(error.type().grammatical() ? onlyTopic : null);
			errors.add(new AnswerError(error.type(), error.span(), error.explanation(), topic));
		}
		boolean correct = verdict.correct();
		return new Evaluation(true, correct, verdict.score(), EvaluatedBy.AI, Set.of(), List.of(), correctAnswer,
				new Feedback(errors, correct ? null : verdict.correctedAnswer(), verdict.feedback()));
	}

	/** The judge's verdict must agree with itself and quote the answer. */
	static void consistent(JudgeAnswer verdict, String answer, OutputProblems problems) {
		if (verdict.correct() == null || verdict.score() == null) {
			return;
		}
		if (verdict.correct()) {
			if (!verdict.errors().isEmpty()) {
				problems.add("errors", "must be empty when correct is true; set correct to false if there are errors");
			}
			if (verdict.score() < 1) {
				problems.add("score", "must be 1 when correct is true");
			}
		}
		else {
			if (verdict.score() >= 1) {
				problems.add("score", "must be below 1 when correct is false");
			}
			if (verdict.errors().isEmpty() && verdict.score() > 0) {
				problems.add("errors", "must list at least one error when correct is false");
			}
		}
		String haystack = AnswerNormalizer.ignoringCase(answer);
		for (int i = 0; i < verdict.errors().size(); i++) {
			JudgeAnswer.Error error = verdict.errors().get(i);
			if (error != null && error.span() != null && !error.span().isBlank()
					&& !contains(haystack, AnswerNormalizer.ignoringCase(error.span()))) {
				problems.add("errors[" + i + "].span",
						"must be copied exactly from the learner's answer, or null for something missing");
			}
		}
	}

	/** Whole-word containment, so "die" isn't found inside "dieser". */
	private static boolean contains(String text, String part) {
		if (part.isEmpty()) {
			return true;
		}
		return (" " + text + " ").contains(" " + part + " ");
	}

	static String typeName(ExerciseType type) {
		return switch (type) {
			case TRANSLATION -> "translation";
			case SENTENCE_CONSTRUCTION -> "sentence construction";
			case ERROR_CORRECTION -> "error correction";
			case TRANSFORMATION -> "transformation";
			default -> throw new IllegalArgumentException(type + " exercises are always checked by the rules");
		};
	}

	/** The exercise's instruction, or what its type asks by default. */
	static String instruction(ExercisePrompt prompt) {
		if (prompt.instruction() != null && !prompt.instruction().isBlank()) {
			return prompt.instruction();
		}
		return switch (prompt) {
			case ExercisePrompt.Translation p -> p.direction() == CardDirection.NATIVE_TO_TARGET
					? "Translate the sentence into the learned language."
					: "Translate the sentence into the explanation language.";
			case ExercisePrompt.SentenceConstruction p -> "Write one sentence that does the task and uses the given words.";
			case ExercisePrompt.ErrorCorrection p -> "Correct the mistake and write the whole sentence.";
			case ExercisePrompt.Transformation p -> "Rewrite the sentence as the task says.";
			default -> throw new IllegalArgumentException(prompt.getClass().getSimpleName() + " isn't graded by AI");
		};
	}

	/** What the learner was shown, as text. */
	static String exercise(ExercisePrompt prompt) {
		return switch (prompt) {
			case ExercisePrompt.Translation p -> p.text();
			case ExercisePrompt.SentenceConstruction p -> p.task() + "\nWords to use: " + String.join(", ", p.words());
			case ExercisePrompt.ErrorCorrection p -> p.sentence();
			case ExercisePrompt.Transformation p -> p.sentence() + "\nTask: " + p.task();
			default -> throw new IllegalArgumentException(prompt.getClass().getSimpleName() + " isn't graded by AI");
		};
	}

	static String describe(GrammarTopicSummary topic) {
		return topic.description() == null || topic.description().isBlank() ? topic.title()
				: topic.title() + ": " + topic.description();
	}

	/** The highest level among the exercise's topics, or else the learner's own estimate. */
	@Nullable String level(UUID userId, String language, List<GrammarTopicSummary> exerciseTopics) {
		Optional<CefrLevel> topicLevel = exerciseTopics.stream()
			.map(GrammarTopicSummary::cefrLevel)
			.filter(Objects::nonNull)
			.max(Enum::compareTo);
		if (topicLevel.isPresent()) {
			return topicLevel.get().name();
		}
		return userLanguages.list(userId)
			.stream()
			.filter(entry -> entry.getLanguageCode().equals(language))
			.map(UserLanguage::getSelfAssessedLevel)
			.filter(Objects::nonNull)
			.map(Enum::name)
			.findFirst()
			.orElse(null);
	}

	static OutputCheck<JudgeAnswer> checkFor(String answer) {
		return (value, problems) -> consistent(value, answer, problems);
	}

}
