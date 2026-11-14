package com.linguaflow.ai.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.linguaflow.ai.prompt.PromptVar.ACCEPTED_ANSWERS;
import static com.linguaflow.ai.prompt.PromptVar.CEFR_LEVEL;
import static com.linguaflow.ai.prompt.PromptVar.COMBINE;
import static com.linguaflow.ai.prompt.PromptVar.COUNT;
import static com.linguaflow.ai.prompt.PromptVar.DIFFICULTY;
import static com.linguaflow.ai.prompt.PromptVar.ERRORS;
import static com.linguaflow.ai.prompt.PromptVar.EXERCISE;
import static com.linguaflow.ai.prompt.PromptVar.EXERCISE_TYPE;
import static com.linguaflow.ai.prompt.PromptVar.EXPLANATION_LANGUAGE;
import static com.linguaflow.ai.prompt.PromptVar.FOCUS_MISTAKES;
import static com.linguaflow.ai.prompt.PromptVar.FOCUS_TOPICS;
import static com.linguaflow.ai.prompt.PromptVar.INSTRUCTION;
import static com.linguaflow.ai.prompt.PromptVar.KNOWN_VOCABULARY;
import static com.linguaflow.ai.prompt.PromptVar.LEARNER_ANSWER;
import static com.linguaflow.ai.prompt.PromptVar.LEARNER_TOPICS;
import static com.linguaflow.ai.prompt.PromptVar.LESSON_VOCABULARY;
import static com.linguaflow.ai.prompt.PromptVar.PART_OF_SPEECH;
import static com.linguaflow.ai.prompt.PromptVar.PRACTICE_VOCABULARY;
import static com.linguaflow.ai.prompt.PromptVar.RECENT_EXERCISES;
import static com.linguaflow.ai.prompt.PromptVar.REFERENCE_ANSWER;
import static com.linguaflow.ai.prompt.PromptVar.TARGET_LANGUAGE;
import static com.linguaflow.ai.prompt.PromptVar.TOPIC_DESCRIPTION;
import static com.linguaflow.ai.prompt.PromptVar.TOPIC_TITLE;
import static com.linguaflow.ai.prompt.PromptVar.TRANSLATION_HINT;
import static com.linguaflow.ai.prompt.PromptVar.WORD;
import static com.linguaflow.ai.prompt.PromptVar.WORDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden files for prompt rendering (QA-03): every template's current version is
 * rendered with fixed sample values, once with only the required variables and once
 * with every variable, and must match {@code src/test/resources/prompt-golden/<id>/
 * v<n>.{minimal,full}.txt} exactly.
 * <p>
 * {@link PromptVersionTests} guards the template <em>files</em>; this test guards what
 * the model actually receives, so changes to the renderer, the input clean-up (AI-07) or
 * how a feature's variables combine show up as a readable diff. After an intended change
 * (or a new version), re-record with
 * {@code ./mvnw test -Dtest=PromptGoldenTests -Dgolden.update=true} and review the diff.
 */
class PromptGoldenTests {

	static final Path GOLDEN = Path.of("src", "test", "resources", "prompt-golden");

	/** A realistic value per variable, as the feature code would pass it. */
	private static final Map<PromptVar, Object> SAMPLES = new EnumMap<>(PromptVar.class);

	static {
		SAMPLES.put(CEFR_LEVEL, "A2");
		SAMPLES.put(KNOWN_VOCABULARY, List.of("das Haus (house)", "laufen (to run)", "die Katze (cat)"));
		SAMPLES.put(FOCUS_TOPICS, List.of("Dativ: The dative case for indirect objects",
				"Perfekt: The spoken past tense with haben or sein"));
		SAMPLES.put(LEARNER_TOPICS, List.of("Akkusativ", "Modalverben"));
		SAMPLES.put(WORD, "Hund");
		SAMPLES.put(WORDS, List.of("Hund", "laufen (verb)", "schön"));
		SAMPLES.put(PART_OF_SPEECH, "NOUN");
		SAMPLES.put(TRANSLATION_HINT, "the animal");
		SAMPLES.put(TOPIC_TITLE, "Dativ");
		SAMPLES.put(TOPIC_DESCRIPTION, "The dative case.\n\nUsed for indirect objects and after some prepositions.");
		SAMPLES.put(COUNT, "4");
		SAMPLES.put(PRACTICE_VOCABULARY, List.of("der Hund (dog)", "geben (to give)"));
		SAMPLES.put(RECENT_EXERCISES, List.of("FILL_BLANK: Ich helfe ___ Mann. → dem"));
		SAMPLES.put(DIFFICULTY, "easy: short, simple sentences with everyday words");
		SAMPLES.put(COMBINE, "yes");
		SAMPLES.put(LESSON_VOCABULARY, List.of("Lektion 1 (2 Nov): der Hund, die Katze", "Lektion 2 (9 Nov): geben"));
		SAMPLES.put(FOCUS_MISTAKES, List.of(
				"WRONG_GAP in Dativ (2 times), e.g. answered \"Ich helfe den Mann.\" where \"Ich helfe dem Mann.\" is right"));
		SAMPLES.put(EXERCISE_TYPE, "TRANSLATION");
		SAMPLES.put(INSTRUCTION, "Translate into German.");
		SAMPLES.put(EXERCISE, "I give the dog water.");
		SAMPLES.put(REFERENCE_ANSWER, "Ich gebe dem Hund Wasser.");
		SAMPLES.put(ACCEPTED_ANSWERS, List.of("Ich gebe Wasser dem Hund."));
		SAMPLES.put(LEARNER_ANSWER, "Ich gebe den Hund Wasser.");
		SAMPLES.put(ERRORS, List.of("WRONG_CASE: \"den Hund\" — geben takes the dative for the receiver"));
	}

	static Stream<Arguments> renderings() {
		List<Arguments> cases = new ArrayList<>();
		for (PromptTemplate template : PromptTemplate.values()) {
			cases.add(Arguments.of(template, "minimal"));
			cases.add(Arguments.of(template, "full"));
		}
		return cases.stream();
	}

	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("renderings")
	void renderingMatchesItsGoldenFile(PromptTemplate template, String scenario) throws IOException {
		String actual = render(template, scenario.equals("full"));
		Path file = GOLDEN.resolve(template.id()).resolve("v" + template.version() + "." + scenario + ".txt");

		if (Boolean.getBoolean("golden.update")) {
			Files.createDirectories(file.getParent());
			Files.writeString(file, actual, StandardCharsets.UTF_8);
			return;
		}
		assertThat(file).as("""
				%s has no golden file yet. Record it with \
				./mvnw test -Dtest=PromptGoldenTests -Dgolden.update=true""".formatted(file)).exists();
		String expected = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
		assertThat(actual).as("""
				The rendered %s (%s) changed. If that's intended, re-record with \
				-Dgolden.update=true and review the diff""".formatted(template.versionTag(), scenario))
			.isEqualTo(expected);
	}

	@Test
	void everyVariableHasASample() {
		for (PromptTemplate template : PromptTemplate.values()) {
			Stream.concat(template.required().stream(), template.optional().stream())
				.filter(variable -> variable != TARGET_LANGUAGE && variable != EXPLANATION_LANGUAGE)
				.forEach(variable -> assertThat(SAMPLES).as("a sample value for %s", variable).containsKey(variable));
		}
	}

	private static String render(PromptTemplate template, boolean full) {
		PromptVariables variables = PromptVariables.forLanguages("de", "en");
		for (PromptVar variable : template.required()) {
			if (variable != TARGET_LANGUAGE && variable != EXPLANATION_LANGUAGE) {
				variables.set(variable, SAMPLES.get(variable));
			}
		}
		if (full) {
			template.optional().forEach(variable -> variables.set(variable, SAMPLES.get(variable)));
		}
		RenderedPrompt rendered = new PromptLibrary().render(template, variables);
		return "# " + template.versionTag() + ", " + (full ? "every variable" : "required variables only")
				+ "\n# input flags: " + rendered.inputFlags().stream().map(Enum::name).sorted().toList()
				+ "\n=== system ===\n" + rendered.prompt().system().replace("\r\n", "\n")
				+ "\n=== user ===\n" + rendered.prompt().user().replace("\r\n", "\n") + "\n";
	}

}
