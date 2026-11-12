package com.linguaflow.exercise;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.linguaflow.ai.OutputProblems;
import org.jspecify.annotations.Nullable;

/**
 * Consistency rules between an exercise's prompt and solution (EX-01), beyond what Bean
 * Validation checks on each record. AI generation (EX-02) uses them as its output check,
 * so the messages are instructions with JSON paths; {@link Exercise} refuses content that
 * breaks them.
 * <ul>
 * <li>Fill in the blank: as many {@code ___} gaps as answers.</li>
 * <li>Multiple choice: distinct options (ignoring case and spacing), and the correct
 * index points at one of them.</li>
 * <li>Word order: the solution and each alternative use exactly the prompt's tokens, and
 * the prompt isn't already in the solved order.</li>
 * <li>Error correction and transformation: the answer differs from the given
 * sentence.</li>
 * <li>Sentence construction: the required words are distinct.</li>
 * </ul>
 */
public final class ExerciseRules {

	/** A gap in a fill-in-the-blank text: three or more underscores. */
	static final Pattern GAP = Pattern.compile("_{3,}");

	private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

	private ExerciseRules() {
	}

	/** The problems of {@code content}; empty when it's consistent. */
	public static List<String> problems(ExerciseContent content) {
		OutputProblems problems = new OutputProblems();
		check(content, problems);
		return problems.list();
	}

	public static void check(ExerciseContent content, OutputProblems problems) {
		switch (content.prompt()) {
			case ExercisePrompt.FillBlank prompt -> checkBlanks(prompt, (ExerciseSolution.Blanks) content.solution(),
					problems);
			case ExercisePrompt.MultipleChoice prompt ->
				checkChoice(prompt, (ExerciseSolution.Choice) content.solution(), problems);
			case ExercisePrompt.WordOrder prompt ->
				checkOrder(prompt, (ExerciseSolution.TokenOrder) content.solution(), problems);
			case ExercisePrompt.ErrorCorrection prompt ->
				requireChange(prompt.sentence(), (ExerciseSolution.TextAnswer) content.solution(),
						"must correct the mistake in the sentence, not repeat it", problems);
			case ExercisePrompt.Transformation prompt ->
				requireChange(prompt.sentence(), (ExerciseSolution.TextAnswer) content.solution(),
						"must be the transformed sentence, not the original", problems);
			case ExercisePrompt.SentenceConstruction prompt ->
				requireDistinct("prompt.words", prompt.words(), "repeats", problems);
			case ExercisePrompt.Translation prompt -> {
				// Nothing to relate: any text has a translation
			}
		}
	}

	/** The number of {@code ___} gaps in a fill-in-the-blank text. */
	public static int gapCount(@Nullable String text) {
		if (text == null) {
			return 0;
		}
		Matcher matcher = GAP.matcher(text);
		int count = 0;
		while (matcher.find()) {
			count++;
		}
		return count;
	}

	private static void checkBlanks(ExercisePrompt.FillBlank prompt, ExerciseSolution.Blanks solution,
			OutputProblems problems) {
		int gaps = gapCount(prompt.text());
		if (gaps == 0) {
			problems.add("prompt.text", "must contain at least one gap written as ___");
		}
		else if (gaps != solution.blanks().size()) {
			problems.add("solution.blanks", "must have one entry per ___ gap: the text has " + gaps
					+ " but there are " + solution.blanks().size());
		}
	}

	private static void checkChoice(ExercisePrompt.MultipleChoice prompt, ExerciseSolution.Choice solution,
			OutputProblems problems) {
		requireDistinct("prompt.options", prompt.options(), "repeats", problems);
		Integer index = solution.correctIndex();
		if (index != null && (index < 0 || index >= prompt.options().size())) {
			problems.add("solution.correctIndex",
					"must be between 0 and " + (prompt.options().size() - 1) + ", the index of the correct option");
		}
	}

	private static void checkOrder(ExercisePrompt.WordOrder prompt, ExerciseSolution.TokenOrder solution,
			OutputProblems problems) {
		Map<String, Integer> expected = counts(prompt.tokens());
		if (!counts(solution.tokens()).equals(expected)) {
			problems.add("solution.tokens", "must use exactly the prompt's tokens, each as often as in the prompt");
		}
		else if (prompt.tokens().equals(solution.tokens()) && expected.size() > 1) {
			problems.add("prompt.tokens", "must be shuffled, not already in the correct order");
		}
		for (int i = 0; i < solution.alternatives().size(); i++) {
			List<String> alternative = solution.alternatives().get(i);
			if (alternative != null && !counts(alternative).equals(expected)) {
				problems.add("solution.alternatives[" + i + "]", "must use exactly the prompt's tokens");
			}
		}
	}

	private static void requireChange(@Nullable String sentence, ExerciseSolution.TextAnswer solution,
			String message, OutputProblems problems) {
		if (sentence != null && solution.answer() != null && normalize(sentence).equals(normalize(solution.answer()))) {
			problems.add("solution.answer", message);
		}
	}

	private static void requireDistinct(String path, List<String> values, String message, OutputProblems problems) {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < values.size(); i++) {
			String value = values.get(i);
			if (value != null && !seen.add(normalize(value))) {
				problems.add(path + "[" + i + "]", message + " an earlier entry; each must be different");
			}
		}
	}

	private static Map<String, Integer> counts(List<String> tokens) {
		Map<String, Integer> counts = new HashMap<>();
		for (String token : tokens) {
			if (token != null) {
				counts.merge(token.strip(), 1, Integer::sum);
			}
		}
		return counts;
	}

	/** NFC, trimmed, single spaces, lower case: for "is this the same text?" checks. */
	static String normalize(String text) {
		String nfc = Normalizer.normalize(text, Normalizer.Form.NFC);
		return WHITESPACE.matcher(nfc.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
	}

}
