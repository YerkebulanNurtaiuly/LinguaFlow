package com.linguaflow.ai.prompt;

import static com.linguaflow.ai.prompt.PromptVar.ACCEPTED_ANSWERS;
import static com.linguaflow.ai.prompt.PromptVar.CEFR_LEVEL;
import static com.linguaflow.ai.prompt.PromptVar.COUNT;
import static com.linguaflow.ai.prompt.PromptVar.ERRORS;
import static com.linguaflow.ai.prompt.PromptVar.EXERCISE;
import static com.linguaflow.ai.prompt.PromptVar.EXERCISE_TYPE;
import static com.linguaflow.ai.prompt.PromptVar.EXPLANATION_LANGUAGE;
import static com.linguaflow.ai.prompt.PromptVar.FOCUS_TOPICS;
import static com.linguaflow.ai.prompt.PromptVar.INSTRUCTION;
import static com.linguaflow.ai.prompt.PromptVar.KNOWN_VOCABULARY;
import static com.linguaflow.ai.prompt.PromptVar.LEARNER_ANSWER;
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

import java.util.EnumSet;
import java.util.Set;

import com.linguaflow.ai.AiUseCase;
import com.linguaflow.common.domain.ExerciseType;

/**
 * Every prompt template (AI-03): its files, use case, current version and variables.
 * <p>
 * The text lives in {@code resources/prompts/<id>/v<version>.system.md} and
 * {@code .user.md}. A released version is never edited: write {@code v<n+1>} files and
 * bump the version here, so {@code ai_requests.prompt_version} and the AI cache key
 * (AI-05, AI-06) always name the exact text used. {@code PromptVersionTests} enforces it.
 * <p>
 * Every template requires {@link PromptVar#TARGET_LANGUAGE} and
 * {@link PromptVar#EXPLANATION_LANGUAGE}; they are the only variables a system prompt
 * may use, so it stays the same across calls and its cache entry is reused.
 */
public enum PromptTemplate {

	VOCABULARY_ENRICHMENT("vocabulary-enrichment", AiUseCase.VOCABULARY_ENRICHMENT, 1, Set.of(WORD),
			Set.of(CEFR_LEVEL, PART_OF_SPEECH, TRANSLATION_HINT, KNOWN_VOCABULARY)),
	VOCABULARY_ENRICHMENT_BATCH("vocabulary-enrichment-batch", AiUseCase.VOCABULARY_ENRICHMENT, 1, Set.of(WORDS),
			Set.of(CEFR_LEVEL, KNOWN_VOCABULARY)),

	GRAMMAR_EXPLANATION("grammar-explanation", AiUseCase.GRAMMAR_EXPLANATION, 1, Set.of(TOPIC_TITLE),
			Set.of(TOPIC_DESCRIPTION, CEFR_LEVEL, KNOWN_VOCABULARY, FOCUS_TOPICS)),

	EXERCISE_FILL_BLANK("exercise-fill-blank"),
	EXERCISE_MULTIPLE_CHOICE("exercise-multiple-choice"),
	EXERCISE_TRANSLATION("exercise-translation"),
	EXERCISE_SENTENCE_CONSTRUCTION("exercise-sentence-construction"),
	EXERCISE_ERROR_CORRECTION("exercise-error-correction"),
	EXERCISE_WORD_ORDER("exercise-word-order"),
	EXERCISE_TRANSFORMATION("exercise-transformation"),

	ANSWER_EVALUATION("answer-evaluation", AiUseCase.ANSWER_EVALUATION, 1,
			Set.of(EXERCISE_TYPE, INSTRUCTION, EXERCISE, REFERENCE_ANSWER, LEARNER_ANSWER),
			Set.of(ACCEPTED_ANSWERS, CEFR_LEVEL, FOCUS_TOPICS)),

	MISTAKE_EXPLANATION("mistake-explanation", AiUseCase.MISTAKE_EXPLANATION, 1,
			Set.of(EXERCISE, REFERENCE_ANSWER, LEARNER_ANSWER), Set.of(INSTRUCTION, ERRORS, CEFR_LEVEL, FOCUS_TOPICS));



	private final String id;

	private final AiUseCase useCase;

	private final int version;

	private final Set<PromptVar> required;

	private final Set<PromptVar> optional;

	/** An exercise generation template; they all take the same variables. */
	PromptTemplate(String id) {
		this(id, AiUseCase.EXERCISE_GENERATION, 1, Set.of(COUNT),
				Set.of(CEFR_LEVEL, FOCUS_TOPICS, PRACTICE_VOCABULARY, KNOWN_VOCABULARY, RECENT_EXERCISES));
	}

	PromptTemplate(String id, AiUseCase useCase, int version, Set<PromptVar> required, Set<PromptVar> optional) {
		this.id = id;
		this.useCase = useCase;
		this.version = version;
		EnumSet<PromptVar> all = EnumSet.of(TARGET_LANGUAGE, EXPLANATION_LANGUAGE);
		all.addAll(required);
		this.required = Set.copyOf(all);
		this.optional = Set.copyOf(optional);
	}

	/** The exercise generation template for {@code type}. */
	public static PromptTemplate exercise(ExerciseType type) {
		return valueOf("EXERCISE_" + type.name());
	}

	/** The directory under {@code resources/prompts/}. */
	public String id() {
		return id;
	}

	public AiUseCase useCase() {
		return useCase;
	}

	public int version() {
		return version;
	}

	/** For {@code ai_requests.prompt_version} and cache keys, e.g. {@code vocabulary-enrichment/v1}. */
	public String versionTag() {
		return id + "/v" + version;
	}

	public Set<PromptVar> required() {
		return required;
	}

	public Set<PromptVar> optional() {
		return optional;
	}

	public boolean allows(PromptVar variable) {
		return required.contains(variable) || optional.contains(variable);
	}

	/** Variables a system prompt may use: the two language names. */
	static Set<PromptVar> stableVariables() {
		return Set.of(TARGET_LANGUAGE, EXPLANATION_LANGUAGE);
	}

}
