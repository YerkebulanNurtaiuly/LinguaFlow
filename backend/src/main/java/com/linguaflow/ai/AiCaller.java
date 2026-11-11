package com.linguaflow.ai;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * On whose behalf an LLM call runs (AI-05): the user whose daily quota it counts against
 * and whose usage it's logged under, and the AI job it belongs to, if any.
 */
public record AiCaller(@Nullable UUID userId, @Nullable UUID jobId) {

	/** A call for no particular user, e.g. a maintenance task; not limited by a quota. */
	public static AiCaller system() {
		return new AiCaller(null, null);
	}

	public static AiCaller user(UUID userId) {
		return new AiCaller(userId, null);
	}

}
