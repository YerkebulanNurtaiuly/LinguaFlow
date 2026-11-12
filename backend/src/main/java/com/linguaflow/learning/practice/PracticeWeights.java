package com.linguaflow.learning.practice;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.linguaflow.common.domain.KnowledgeStatus;
import org.jspecify.annotations.Nullable;

/**
 * Turns what's known about a word or topic into a {@link PracticeCandidate} (PRC-01).
 * Pure and deterministic: the same stats and {@code now} give the same scores.
 * <ul>
 * <li><b>Fresh:</b> added in the last {@value #FRESH_DAYS} days, or never practised.</li>
 * <li><b>Word weakness:</b> 0.4 × how far the lowest card ease is below 2.5 (1.3 counts
 * as fully weak), + 0.3 × lapses (4 or more is fully weak), + 0.3 if a card is
 * (re)learning. A word never reviewed is 0.5, unknown. MASTERED halves it.</li>
 * <li><b>Topic weakness:</b> 1 − the average score of its latest exercise attempts, or 0.5
 * without attempts. MASTERED halves it.</li>
 * <li><b>Staleness:</b> days since the last review or exercise attempt, up to
 * {@value #STALE_DAYS} days = 1; never practised = 1.</li>
 * <li><b>Dueness (words):</b> 1 when a card is due, falling to 0 a week ahead; 0.5 for a
 * word never reviewed; 0 without any due date. Topics have no review schedule, so their
 * dueness is their staleness.</li>
 * </ul>
 */
public final class PracticeWeights {

	static final int FRESH_DAYS = 7;

	static final int STALE_DAYS = 30;

	private static final double DEFAULT_EASE = 2.5;

	private static final double MIN_EASE = 1.3;

	private PracticeWeights() {
	}

	/**
	 * What's known about one word.
	 * @param lowestEase the lowest ease among its cards; null without reviews
	 * @param maxLapses the most lapses on one of its cards
	 * @param learning a card is LEARNING or RELEARNING
	 * @param lastReviewedAt its latest card review
	 * @param earliestDueAt the earliest due date among its cards
	 * @param lastAttemptAt its latest exercise attempt
	 */
	public record WordStats(UUID id, Instant createdAt, KnowledgeStatus status, @Nullable Double lowestEase,
			int maxLapses, boolean learning, @Nullable Instant lastReviewedAt, @Nullable Instant earliestDueAt,
			@Nullable Instant lastAttemptAt) {
	}

	/**
	 * What's known about one grammar topic.
	 * @param recentScore the average score of its latest attempts (0–1); null without any
	 */
	public record TopicStats(UUID id, Instant createdAt, KnowledgeStatus status, int attempts,
			@Nullable Double recentScore, @Nullable Instant lastAttemptAt) {
	}

	public static PracticeCandidate word(WordStats word, Instant now) {
		Instant lastPracticed = latest(word.lastReviewedAt(), word.lastAttemptAt());
		double weakness;
		if (word.lastReviewedAt() == null) {
			weakness = 0.5;
		}
		else {
			double ease = word.lowestEase() == null ? DEFAULT_EASE : word.lowestEase();
			weakness = 0.4 * PracticeCandidate.clamp((DEFAULT_EASE - ease) / (DEFAULT_EASE - MIN_EASE))
					+ 0.3 * Math.min(word.maxLapses(), 4) / 4.0 + (word.learning() ? 0.3 : 0);
		}
		if (word.status() == KnowledgeStatus.MASTERED) {
			weakness /= 2;
		}
		double staleness = staleness(lastPracticed, now);
		double dueness;
		if (word.lastReviewedAt() == null) {
			dueness = 0.5;
		}
		else if (word.earliestDueAt() == null) {
			dueness = 0;
		}
		else {
			double daysAhead = Duration.between(now, word.earliestDueAt()).toMinutes() / (24.0 * 60);
			dueness = daysAhead <= 0 ? 1 : 1 - daysAhead / 7;
		}
		return new PracticeCandidate(word.id(), fresh(word.createdAt(), lastPracticed, now), weakness, staleness,
				dueness);
	}

	public static PracticeCandidate topic(TopicStats topic, Instant now) {
		double weakness = topic.attempts() == 0 || topic.recentScore() == null ? 0.5 : 1 - topic.recentScore();
		if (topic.status() == KnowledgeStatus.MASTERED) {
			weakness /= 2;
		}
		double staleness = staleness(topic.lastAttemptAt(), now);
		return new PracticeCandidate(topic.id(), fresh(topic.createdAt(), topic.lastAttemptAt(), now), weakness,
				staleness, staleness);
	}

	private static boolean fresh(Instant createdAt, @Nullable Instant lastPracticed, Instant now) {
		return lastPracticed == null || Duration.between(createdAt, now).toDays() < FRESH_DAYS;
	}

	private static double staleness(@Nullable Instant lastPracticed, Instant now) {
		if (lastPracticed == null) {
			return 1;
		}
		double days = Duration.between(lastPracticed, now).toMinutes() / (24.0 * 60);
		return Math.min(days, STALE_DAYS) / STALE_DAYS;
	}

	private static @Nullable Instant latest(@Nullable Instant a, @Nullable Instant b) {
		if (a == null) {
			return b;
		}
		return b == null || a.isAfter(b) ? a : b;
	}

}
