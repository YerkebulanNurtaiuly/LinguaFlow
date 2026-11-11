package com.linguaflow.ai.job;

/** What an AI job does; same values as the {@code ai_jobs_type_ck} CHECK constraint. */
public enum AiJobType {

	/** Enrich a batch of words (VOC-05). */
	VOCABULARY_ENRICHMENT,
	/** Explain a grammar topic (GRM-04). */
	GRAMMAR_EXPLANATION,
	/** Write an exercise set (EX-02). */
	EXERCISE_GENERATION,
	/** Grade open-ended answers (EX-04). */
	ANSWER_EVALUATION

}
