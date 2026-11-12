package com.linguaflow.learning.practice;

import java.util.UUID;

/**
 * A word or grammar topic that practice could pick, scored by {@link PracticeWeights}.
 * Every score is 0–1.
 * @param fresh new to the learner: added recently or never practised
 * @param weakness how badly the learner knows it
 * @param staleness how long ago it was last practised
 * @param dueness how due it is for review (words: the SRS due date; topics: staleness)
 */
public record PracticeCandidate(UUID id, boolean fresh, double weakness, double staleness, double dueness) {

	public PracticeCandidate {
		weakness = clamp(weakness);
		staleness = clamp(staleness);
		dueness = clamp(dueness);
	}

	static double clamp(double value) {
		return Double.isNaN(value) ? 0 : Math.max(0, Math.min(1, value));
	}

}
