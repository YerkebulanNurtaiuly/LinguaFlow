package com.linguaflow.progress;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.linguaflow.common.domain.KnowledgeStatus;
import org.jspecify.annotations.Nullable;

/**
 * How weak a grammar topic or word is (PRG-03), 0 (solid) to 1 (weakest), with the
 * reasons. Pure and deterministic; the inputs come from the progress aggregates.
 * <ul>
 * <li><b>Error rate:</b> 1 − recent accuracy over the latest answers (at most
 * {@value ProgressAggregates#RECENT_ANSWERS}), smoothed towards 0.5 with
 * {@value #PRIOR_ANSWERS} imaginary answers, so two lucky or unlucky answers don't
 * decide.</li>
 * <li><b>Staleness:</b> days since the last practice over {@value #STALE_DAYS}, at most 1:
 * what isn't practised fades.</li>
 * <li><b>Lapses</b> (words): the most lapses on one of its cards, at most
 * {@value #MAX_LAPSES}.</li>
 * <li><b>Topics:</b> 0.75 × error rate + 0.25 × staleness.</li>
 * <li><b>Words:</b> 0.5 × error rate + 0.3 × lapses + 0.2 × staleness.</li>
 * <li><b>Mastery:</b> MASTERED halves the score, as in practice selection (PRC-01).</li>
 * </ul>
 */
public final class WeaknessScores {

	static final double PRIOR_ANSWERS = 2;

	static final int STALE_DAYS = 30;

	static final int MAX_LAPSES = 4;

	/** Below this recent accuracy, low accuracy is a reason. */
	static final double LOW_ACCURACY = 0.7;

	/** From this many days without practice, that's a reason. */
	static final int STALE_REASON_DAYS = 14;

	/** From this many lapses on a card, that's a reason. */
	static final int LAPSES_REASON = 2;

	/** From this many mistakes of one type, a topic's most frequent mistake is a reason. */
	static final int MISTAKES_REASON = 2;

	private WeaknessScores() {
	}

	/** Why something counts as weak. */
	public enum ReasonCode {

		/** {@code value} is the recent accuracy, 0–1. */
		LOW_ACCURACY,
		/** {@code value} is how many mistakes of {@code errorType} were made on the topic. */
		FREQUENT_MISTAKE,
		/** {@code value} is the most lapses on one of the word's cards. */
		LAPSES,
		/** {@code value} is the days since the last practice. */
		NOT_PRACTISED_RECENTLY

	}

	/** One reason, with the number behind it. */
	public record Reason(ReasonCode code, double value, @Nullable String errorType) {
	}

	public record Weakness(double score, List<Reason> reasons) {

		public Weakness {
			reasons = List.copyOf(reasons);
		}

	}

	/**
	 * A topic's record.
	 * @param answers attempts in the rolling window (the newest ones)
	 * @param topMistake its most frequent mistake type, null without mistakes
	 */
	public record TopicInput(KnowledgeStatus status, int answers, @Nullable Double recentAccuracy,
			@Nullable Instant lastPracticedAt, @Nullable String topMistake, int topMistakeCount) {
	}

	/** A word's record. */
	public record WordInput(KnowledgeStatus status, int answers, @Nullable Double recentAccuracy, int lapses,
			@Nullable Instant lastPracticedAt) {
	}

	public static Weakness topic(TopicInput topic, Instant now) {
		List<Reason> reasons = new ArrayList<>();
		double error = errorRate(topic.answers(), topic.recentAccuracy(), reasons);
		double staleness = staleness(topic.lastPracticedAt(), now, reasons);
		if (topic.topMistake() != null && topic.topMistakeCount() >= MISTAKES_REASON) {
			reasons.add(new Reason(ReasonCode.FREQUENT_MISTAKE, topic.topMistakeCount(), topic.topMistake()));
		}
		return weakness(0.75 * error + 0.25 * staleness, topic.status(), reasons);
	}

	public static Weakness word(WordInput word, Instant now) {
		List<Reason> reasons = new ArrayList<>();
		double error = errorRate(word.answers(), word.recentAccuracy(), reasons);
		if (word.lapses() >= LAPSES_REASON) {
			reasons.add(new Reason(ReasonCode.LAPSES, word.lapses(), null));
		}
		double lapses = Math.min(word.lapses(), MAX_LAPSES) / (double) MAX_LAPSES;
		double staleness = staleness(word.lastPracticedAt(), now, reasons);
		return weakness(0.5 * error + 0.3 * lapses + 0.2 * staleness, word.status(), reasons);
	}

	/** The smoothed error rate; adds LOW_ACCURACY when the accuracy itself is low. */
	private static double errorRate(int answers, @Nullable Double accuracy, List<Reason> reasons) {
		if (accuracy == null || answers <= 0) {
			return 0.5;
		}
		if (accuracy < LOW_ACCURACY) {
			reasons.add(new Reason(ReasonCode.LOW_ACCURACY, accuracy, null));
		}
		double n = Math.min(answers, ProgressAggregates.RECENT_ANSWERS);
		return (n * (1 - accuracy) + PRIOR_ANSWERS * 0.5) / (n + PRIOR_ANSWERS);
	}

	private static double staleness(@Nullable Instant lastPracticed, Instant now, List<Reason> reasons) {
		if (lastPracticed == null) {
			return 1;
		}
		double days = Math.max(0, Duration.between(lastPracticed, now).toMinutes() / (24.0 * 60));
		if (days >= STALE_REASON_DAYS) {
			reasons.add(new Reason(ReasonCode.NOT_PRACTISED_RECENTLY, Math.floor(days), null));
		}
		return Math.min(days, STALE_DAYS) / STALE_DAYS;
	}

	private static Weakness weakness(double score, KnowledgeStatus status, List<Reason> reasons) {
		double result = status == KnowledgeStatus.MASTERED ? score / 2 : score;
		return new Weakness(Math.round(Math.min(1, Math.max(0, result)) * 1000) / 1000.0, reasons);
	}

}
