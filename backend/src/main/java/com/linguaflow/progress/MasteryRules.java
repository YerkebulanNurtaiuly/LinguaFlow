package com.linguaflow.progress;

import java.util.Optional;

import com.linguaflow.common.config.LinguaFlowProperties.Mastery;
import com.linguaflow.common.domain.KnowledgeStatus;
import org.jspecify.annotations.Nullable;

/**
 * When practice earns or loses mastery (PRG-02). Pure and deterministic: the inputs are
 * the progress aggregates (PRG-01) and the thresholds from {@link Mastery}.
 */
public final class MasteryRules {

	private MasteryRules() {
	}

	/** A topic's practice record, from {@code grammar_topic_progress}. */
	public record TopicRecord(int attempts, @Nullable Double recentAccuracy, int practiceDays) {
	}

	/**
	 * The status a topic should move to, if any: MASTERED when the recent accuracy reaches
	 * the threshold over enough attempts on enough days; a MASTERED topic back to LEARNED
	 * when its recent accuracy drops below the demotion threshold. Between the two
	 * thresholds nothing changes, so a topic doesn't flip back and forth.
	 */
	public static Optional<KnowledgeStatus> topic(KnowledgeStatus current, TopicRecord record, Mastery rules) {
		Double accuracy = record.recentAccuracy();
		if (accuracy == null) {
			return Optional.empty();
		}
		if (current == KnowledgeStatus.MASTERED) {
			return accuracy < rules.topicDemoteBelow() ? Optional.of(KnowledgeStatus.LEARNED) : Optional.empty();
		}
		boolean earned = record.attempts() >= rules.topicMinAttempts()
				&& record.practiceDays() >= rules.topicMinDays() && accuracy >= rules.topicAccuracy();
		return earned ? Optional.of(KnowledgeStatus.MASTERED) : Optional.empty();
	}

	/**
	 * The status a word should move to, if any: MASTERED once its cards' longest interval
	 * reaches the threshold, LEARNED again when a lapse brings it below.
	 */
	public static Optional<KnowledgeStatus> word(KnowledgeStatus current, int intervalDays, Mastery rules) {
		boolean reached = intervalDays >= rules.wordIntervalDays();
		if (current == KnowledgeStatus.MASTERED) {
			return reached ? Optional.empty() : Optional.of(KnowledgeStatus.LEARNED);
		}
		return reached ? Optional.of(KnowledgeStatus.MASTERED) : Optional.empty();
	}

}
