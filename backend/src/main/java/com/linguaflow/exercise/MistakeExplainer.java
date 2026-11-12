package com.linguaflow.exercise;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;

import com.linguaflow.ai.AiCaller;
import com.linguaflow.ai.AiErrors;
import com.linguaflow.ai.AiUseCase;
import com.linguaflow.ai.LlmException;
import com.linguaflow.ai.OutputCheck;
import com.linguaflow.ai.StructuredLlm;
import com.linguaflow.ai.StructuredResponse;
import com.linguaflow.ai.cache.AiCacheKey;
import com.linguaflow.ai.prompt.PromptInputFlag;
import com.linguaflow.ai.prompt.PromptLibrary;
import com.linguaflow.ai.prompt.PromptTemplate;
import com.linguaflow.ai.prompt.PromptVar;
import com.linguaflow.ai.prompt.PromptVariables;
import com.linguaflow.ai.prompt.RenderedPrompt;
import com.linguaflow.common.error.ConflictException;
import com.linguaflow.common.error.NotFoundException;
import com.linguaflow.grammar.GrammarTopicService;
import com.linguaflow.grammar.GrammarTopicSummary;
import com.linguaflow.grammar.TitleNormalizer;
import com.linguaflow.user.UserLanguageService;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explains a wrong answer (EX-06), on request: each explanation is an AI call, so none
 * is written automatically.
 * <ul>
 * <li><b>The prompt</b> ({@code mistake-explanation} v1) has the exercise as the learner
 * saw it, the correct answer, the learner's answer as text (a gap text with their words
 * filled in, the option they chose, their word order), the errors the AI judge found, the
 * exercise's topics and the level. It's written in the explanation language.</li>
 * <li><b>Caching:</b> per (exercise, explanation language, normalized wrong answer), so
 * asking again, or repeating the same wrong answer, costs nothing. Only short answers
 * (at most {@value #MAX_CACHED_ANSWER} characters after normalizing) without redacted
 * personal data are cached; schema.md Q5. The key holds the exercise's ID and the
 * answer, never the user's ID.</li>
 * <li><b>Topic:</b> the rule's topic is matched back to the exercise's topics by
 * normalized title, falling back to the exercise's only topic.</li>
 * </ul>
 */
@Service
public class MistakeExplainer {

	/** Longest normalized answer whose explanation is cached. */
	static final int MAX_CACHED_ANSWER = 100;

	private final ExerciseAttemptRepository attempts;

	private final ExerciseRepository exercises;

	private final ExerciseSetRepository sets;

	private final GrammarTopicService topics;

	private final UserLanguageService userLanguages;

	private final AnswerChecker checker;

	private final StructuredLlm llm;

	private final PromptLibrary prompts;

	private final Clock clock;

	private final TransactionTemplate tx;

	MistakeExplainer(ExerciseAttemptRepository attempts, ExerciseRepository exercises, ExerciseSetRepository sets,
			GrammarTopicService topics, UserLanguageService userLanguages, AnswerChecker checker, StructuredLlm llm,
			PromptLibrary prompts, Clock clock, PlatformTransactionManager transactions) {
		this.attempts = attempts;
		this.exercises = exercises;
		this.sets = sets;
		this.topics = topics;
		this.userLanguages = userLanguages;
		this.checker = checker;
		this.llm = llm;
		this.prompts = prompts;
		this.clock = clock;
		this.tx = new TransactionTemplate(transactions);
		this.tx.setReadOnly(true);
	}

	/**
	 * An explanation and whether it came from the cache.
	 * @param grammarTopicId the exercise topic the rule belongs to, if any
	 */
	public record Explained(MistakeExplanation explanation, @Nullable UUID grammarTopicId, boolean cached) {
	}

	private record Loaded(String languageCode, ExerciseContent content, ExerciseAnswer answer,
			Evaluation.@Nullable Feedback feedback, Set<UUID> grammarTopicIds) {
	}

	/**
	 * @throws NotFoundException when the attempt isn't the user's attempt at that exercise
	 * @throws ConflictException when the answer was right
	 */
	public Explained explain(UUID userId, UUID exerciseId, UUID attemptId) {
		Loaded loaded = Objects.requireNonNull(tx.execute(status -> {
			ExerciseAttempt attempt = attempts.requireOwned(attemptId, userId);
			if (!attempt.getExerciseId().equals(exerciseId)) {
				throw new NotFoundException("Resource not found");
			}
			if (attempt.isCorrect()) {
				throw new ConflictException("The answer was right; there's no mistake to explain.");
			}
			Exercise exercise = exercises.requireOwned(exerciseId, userId);
			ExerciseSet set = sets.requireOwned(exercise.getExerciseSetId(), userId);
			ExerciseAnswer answer = ExerciseContent.fromJson(attempt.getAnswer(),
					ExerciseAnswer.typeFor(exercise.getType()));
			return new Loaded(set.getLanguageCode(), exercise.content(), answer, attempt.getFeedback(),
					exercise.getGrammarTopicIds());
		}));
		String language = loaded.languageCode();
		String explanationLanguage = userLanguages.explanationLanguageFor(userId, language);
		List<GrammarTopicSummary> exerciseTopics = topics.summaries(userId, language, loaded.grammarTopicIds());
		ExerciseContent content = loaded.content();
		String learnerAnswer = answerText(content, loaded.answer());
		String reference = AnswerEvaluator.evaluate(content, loaded.answer(), AnswerEvaluator.Options.DEFAULT)
			.correctAnswer();

		RenderedPrompt prompt = prompts.render(PromptTemplate.MISTAKE_EXPLANATION,
				PromptVariables.forLanguages(language, explanationLanguage)
					.set(PromptVar.CEFR_LEVEL, checker.level(userId, language, exerciseTopics))
					.set(PromptVar.INSTRUCTION, content.prompt().instruction())
					.set(PromptVar.EXERCISE, exerciseText(content.prompt()))
					.set(PromptVar.REFERENCE_ANSWER, reference)
					.set(PromptVar.LEARNER_ANSWER, learnerAnswer)
					.set(PromptVar.ERRORS, loaded.feedback() == null ? List.of()
							: loaded.feedback().errors().stream().map(MistakeExplainer::describe).toList())
					.set(PromptVar.FOCUS_TOPICS, exerciseTopics.stream().map(AnswerChecker::describe).toList()));

		StructuredResponse<MistakeExplanation> response;
		try {
			String normalized = AnswerNormalizer.ignoringCase(learnerAnswer);
			if (normalized.length() <= MAX_CACHED_ANSWER
					&& !prompt.inputFlags().contains(PromptInputFlag.PERSONAL_DATA_REDACTED)) {
				AiCacheKey key = AiCacheKey.of(AiUseCase.MISTAKE_EXPLANATION, language,
						PromptTemplate.MISTAKE_EXPLANATION.versionTag(), explanationLanguage, exerciseId.toString(),
						normalized);
				response = llm.completeCached(AiCaller.user(userId), prompt, key, MistakeExplanation.class,
						OutputCheck.none());
			}
			else {
				response = llm.complete(AiCaller.user(userId), prompt, MistakeExplanation.class, OutputCheck.none());
			}
		}
		catch (LlmException ex) {
			throw AiErrors.toApiException(ex, clock);
		}
		MistakeExplanation explanation = response.value();
		return new Explained(explanation, topicOf(language, explanation.topic(), exerciseTopics), response.cached());
	}

	private static @Nullable UUID topicOf(String language, @Nullable String title,
			List<GrammarTopicSummary> exerciseTopics) {
		Map<String, UUID> byTitle = new HashMap<>();
		exerciseTopics.forEach(topic -> byTitle.put(topic.normalizedTitle(), topic.id()));
		if (title != null) {
			String bare = title.contains(":") ? title.substring(0, title.indexOf(':')) : title;
			UUID id = byTitle.get(TitleNormalizer.normalize(language, bare));
			if (id != null) {
				return id;
			}
		}
		return exerciseTopics.size() == 1 ? exerciseTopics.get(0).id() : null;
	}

	private static String describe(Evaluation.AnswerError error) {
		String what = error.span() == null ? "something missing" : "\"" + error.span() + "\"";
		return error.type().name() + ": " + what + " — " + error.explanation();
	}

	/** The exercise as the learner saw it. */
	static String exerciseText(ExercisePrompt prompt) {
		return switch (prompt) {
			case ExercisePrompt.FillBlank p -> p.hint() == null ? p.text() : p.text() + " " + p.hint();
			case ExercisePrompt.MultipleChoice p -> p.question() + "\nOptions: " + String.join(" / ", p.options());
			case ExercisePrompt.WordOrder p -> "Put in order: " + String.join(" / ", p.tokens());
			default -> AnswerChecker.exercise(prompt);
		};
	}

	/** The learner's answer as one text. */
	static String answerText(ExerciseContent content, ExerciseAnswer answer) {
		return switch (answer) {
			case ExerciseAnswer.Blanks blanks -> {
				String text = ((ExercisePrompt.FillBlank) content.prompt()).text();
				StringBuilder filled = new StringBuilder();
				Matcher gap = ExerciseRules.GAP.matcher(text);
				int i = 0;
				while (gap.find()) {
					String typed = i < blanks.blanks().size() && blanks.blanks().get(i) != null
							&& !blanks.blanks().get(i).isBlank() ? blanks.blanks().get(i) : "(nothing)";
					gap.appendReplacement(filled, Matcher.quoteReplacement(typed));
					i++;
				}
				gap.appendTail(filled);
				yield filled.toString();
			}
			case ExerciseAnswer.Choice choice -> {
				List<String> options = ((ExercisePrompt.MultipleChoice) content.prompt()).options();
				yield choice.index() != null && choice.index() >= 0 && choice.index() < options.size()
						? options.get(choice.index()) : "(no option)";
			}
			case ExerciseAnswer.Tokens tokens -> String.join(" ", tokens.tokens().stream().map(String::valueOf).toList());
			case ExerciseAnswer.Text text -> text.text() == null || text.text().isBlank() ? "(nothing)" : text.text();
		};
	}

}
