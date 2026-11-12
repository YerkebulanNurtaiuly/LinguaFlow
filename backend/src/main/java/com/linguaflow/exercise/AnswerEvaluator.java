package com.linguaflow.exercise;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import com.linguaflow.common.config.LinguaFlowProperties;
import com.linguaflow.exercise.Evaluation.EvaluatedBy;
import com.linguaflow.exercise.Evaluation.Note;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Checks answers in code (EX-03). Closed formats are always decided here; open ones only
 * when the answer matches a known one, otherwise they go to the AI judge (EX-04).
 * <ul>
 * <li><b>Fill in the blank:</b> each gap against its answer and alternatives; the score
 * is the share of right gaps, and the answer is correct only with all of them.</li>
 * <li><b>Multiple choice:</b> the chosen index against the correct one.</li>
 * <li><b>Word order:</b> correct when it equals the solution or an alternative (tokens
 * compared ignoring case and punctuation). Otherwise partial credit: the longest run of
 * tokens in the right relative order (longest common subsequence) over the length,
 * against the closest correct order, with {@code parts} marking those tokens.</li>
 * <li><b>Translation, sentence construction, error correction, transformation:</b> a
 * match with the answer or an alternative is correct; anything else is undecided.</li>
 * </ul>
 * Text is compared with {@link AnswerNormalizer}: punctuation and spacing never matter;
 * capitalization is accepted with a note unless {@code strictCase}; ae/oe/ue/ss for
 * ä/ö/ü/ß are accepted with a note when {@code umlautAlternatives} is on
 * ({@code linguaflow.exercises.evaluation.*}).
 */
@Component
public class AnswerEvaluator {

	/** How lenient the comparison is. */
	public record Options(boolean umlautAlternatives, boolean strictCase) {

		public static final Options DEFAULT = new Options(true, false);

	}

	private final Options options;

	AnswerEvaluator(LinguaFlowProperties properties) {
		LinguaFlowProperties.Evaluation settings = properties.exercises().evaluation();
		this.options = new Options(settings.umlautAlternatives(), settings.strictCase());
	}

	public Evaluation evaluate(ExerciseContent content, ExerciseAnswer answer) {
		return evaluate(content, answer, options);
	}

	/**
	 * @throws IllegalArgumentException when the answer isn't the shape the type takes
	 * ({@link ExerciseAnswer#typeFor})
	 */
	public static Evaluation evaluate(ExerciseContent content, ExerciseAnswer answer, Options options) {
		Class<? extends ExerciseAnswer> expected = ExerciseAnswer.typeFor(content.type());
		if (!expected.isInstance(answer)) {
			throw new IllegalArgumentException(
					"A " + content.type() + " exercise takes a " + expected.getSimpleName() + " answer");
		}
		return switch (content.solution()) {
			case ExerciseSolution.Blanks solution -> blanks((ExercisePrompt.FillBlank) content.prompt(), solution,
					(ExerciseAnswer.Blanks) answer, options);
			case ExerciseSolution.Choice solution ->
				choice((ExercisePrompt.MultipleChoice) content.prompt(), solution, (ExerciseAnswer.Choice) answer);
			case ExerciseSolution.TokenOrder solution -> order(solution, (ExerciseAnswer.Tokens) answer);
			case ExerciseSolution.TextAnswer solution -> text(solution, (ExerciseAnswer.Text) answer, options);
		};
	}

	private static Evaluation blanks(ExercisePrompt.FillBlank prompt, ExerciseSolution.Blanks solution,
			ExerciseAnswer.Blanks answer, Options options) {
		Set<Note> notes = EnumSet.noneOf(Note.class);
		List<Boolean> parts = new ArrayList<>();
		int right = 0;
		for (int i = 0; i < solution.blanks().size(); i++) {
			ExerciseSolution.Blank blank = solution.blanks().get(i);
			String given = i < answer.blanks().size() ? answer.blanks().get(i) : null;
			Optional<Set<Note>> match = match(given, blank.answer(), blank.alternatives(), options);
			match.ifPresent(notes::addAll);
			parts.add(match.isPresent());
			right += match.isPresent() ? 1 : 0;
		}
		int total = solution.blanks().size();
		return new Evaluation(true, right == total, (double) right / total, EvaluatedBy.RULES, notes, parts,
				filled(prompt.text(), solution));
	}

	/** The text with each gap filled in. */
	private static String filled(String text, ExerciseSolution.Blanks solution) {
		StringBuilder result = new StringBuilder();
		var matcher = ExerciseRules.GAP.matcher(text);
		int i = 0;
		while (matcher.find()) {
			String answer = i < solution.blanks().size() ? solution.blanks().get(i).answer() : matcher.group();
			matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(answer));
			i++;
		}
		matcher.appendTail(result);
		return result.toString();
	}

	private static Evaluation choice(ExercisePrompt.MultipleChoice prompt, ExerciseSolution.Choice solution,
			ExerciseAnswer.Choice answer) {
		boolean right = answer.index() != null && answer.index().equals(solution.correctIndex());
		return new Evaluation(true, right, right ? 1 : 0, EvaluatedBy.RULES, Set.of(), List.of(),
				prompt.options().get(solution.correctIndex()));
	}

	private static Evaluation order(ExerciseSolution.TokenOrder solution, ExerciseAnswer.Tokens answer) {
		List<String> given = keys(answer.tokens());
		List<List<String>> orders = new ArrayList<>();
		orders.add(solution.tokens());
		orders.addAll(solution.alternatives());
		String correctAnswer = String.join(" ", solution.tokens());

		List<Boolean> bestParts = Collections.nCopies(given.size(), false);
		double best = 0;
		for (int i = 0; i < orders.size(); i++) {
			List<String> order = keys(orders.get(i));
			if (order.equals(given)) {
				return new Evaluation(true, true, 1, EvaluatedBy.RULES, i == 0 ? Set.of() : Set.of(Note.ALTERNATIVE),
						Collections.nCopies(given.size(), true), correctAnswer);
			}
			List<Boolean> parts = commonSubsequence(given, order);
			int length = Math.max(given.size(), order.size());
			double score = length == 0 ? 0 : (double) parts.stream().filter(Boolean::booleanValue).count() / length;
			if (score > best) {
				best = score;
				bestParts = parts;
			}
		}
		// Never full marks for a wrong order, however long the sentence
		return new Evaluation(true, false, Math.min(best, 0.999), EvaluatedBy.RULES, Set.of(), bestParts,
				correctAnswer);
	}

	/** Comparison keys for tokens: punctuation, spacing and case don't matter. */
	private static List<String> keys(List<String> tokens) {
		return tokens.stream().map(AnswerNormalizer::ignoringCase).toList();
	}

	/**
	 * Which of {@code given}'s tokens belong to a longest common subsequence with
	 * {@code target}: the most tokens that are already in the right relative order.
	 */
	static List<Boolean> commonSubsequence(List<String> given, List<String> target) {
		int n = given.size();
		int m = target.size();
		int[][] lengths = new int[n + 1][m + 1];
		for (int i = n - 1; i >= 0; i--) {
			for (int j = m - 1; j >= 0; j--) {
				lengths[i][j] = given.get(i).equals(target.get(j)) ? lengths[i + 1][j + 1] + 1
						: Math.max(lengths[i + 1][j], lengths[i][j + 1]);
			}
		}
		List<Boolean> inPlace = new ArrayList<>(Collections.nCopies(n, false));
		int i = 0;
		int j = 0;
		while (i < n && j < m) {
			if (given.get(i).equals(target.get(j))) {
				inPlace.set(i, true);
				i++;
				j++;
			}
			else if (lengths[i + 1][j] >= lengths[i][j + 1]) {
				i++;
			}
			else {
				j++;
			}
		}
		return inPlace;
	}

	private static Evaluation text(ExerciseSolution.TextAnswer solution, ExerciseAnswer.Text answer,
			Options options) {
		if (AnswerNormalizer.exact(answer.text()).isEmpty()) {
			return new Evaluation(true, false, 0, EvaluatedBy.RULES, Set.of(), List.of(), solution.answer());
		}
		return match(answer.text(), solution.answer(), solution.alternatives(), options)
			.map(notes -> new Evaluation(true, true, 1, EvaluatedBy.RULES, notes, List.of(), solution.answer()))
			.orElseGet(() -> Evaluation.undecided(solution.answer()));
	}

	/**
	 * Whether {@code given} matches the answer or an alternative, trying the strictest
	 * comparison first; the notes say what was accepted.
	 */
	static Optional<Set<Note>> match(@Nullable String given, String answer, List<String> alternatives,
			Options options) {
		if (given == null || AnswerNormalizer.exact(given).isEmpty()) {
			return Optional.empty();
		}
		List<Comparison> comparisons = new ArrayList<>();
		comparisons.add(new Comparison(AnswerNormalizer::exact, Set.of()));
		if (!options.strictCase()) {
			comparisons.add(new Comparison(AnswerNormalizer::ignoringCase, Set.of(Note.CAPITALIZATION)));
		}
		if (options.umlautAlternatives()) {
			comparisons.add(new Comparison(text -> AnswerNormalizer.foldUmlauts(AnswerNormalizer.exact(text)),
					Set.of(Note.UMLAUT_SPELLING)));
			if (!options.strictCase()) {
				comparisons.add(new Comparison(text -> AnswerNormalizer.foldUmlauts(AnswerNormalizer.ignoringCase(text)),
						Set.of(Note.UMLAUT_SPELLING, Note.CAPITALIZATION)));
			}
		}
		for (Comparison comparison : comparisons) {
			String key = comparison.normalize().apply(given);
			if (key.equals(comparison.normalize().apply(answer))) {
				return Optional.of(comparison.notes());
			}
			for (String alternative : alternatives) {
				if (key.equals(comparison.normalize().apply(alternative))) {
					Set<Note> notes = EnumSet.of(Note.ALTERNATIVE);
					notes.addAll(comparison.notes());
					return Optional.of(notes);
				}
			}
		}
		return Optional.empty();
	}

	private record Comparison(UnaryOperator<String> normalize, Set<Note> notes) {
	}

}
