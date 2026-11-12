package com.linguaflow.learning.practice;

/** How practice material is chosen (PRC-01); matches {@code practice_sessions_mode_ck}. */
public enum PracticeMode {

	/** The latest (or a chosen) study session's material. */
	CURRENT_LESSON,
	/** Mostly new material, the rest older material that's weak or hasn't been practised lately. */
	MIXED,
	/** Everything, weighted by weakness and how due it is for review. */
	FULL_REVISION,
	/** The weakest material first (PRG-06). */
	WEAK_SPOTS

}
