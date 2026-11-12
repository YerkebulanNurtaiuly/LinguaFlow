package com.linguaflow.learning.practice;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.learning.practice.PracticeWeights.TopicStats;
import com.linguaflow.learning.practice.PracticeWeights.WordStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PracticeWeightsTests {

	private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

	private static final UUID ID = UUID.randomUUID();

	private static Instant daysAgo(double days) {
		return NOW.minus(Duration.ofMinutes(Math.round(days * 24 * 60)));
	}

	private static WordStats reviewedWord(double ease, int lapses, boolean learning, Instant lastReviewed,
			Instant due) {
		return new WordStats(ID, daysAgo(60), KnowledgeStatus.LEARNING, ease, lapses, learning, lastReviewed, due,
				null);
	}

	@Test
	void aNeverReviewedWordIsFreshAndHalfKnown() {
		PracticeCandidate word = PracticeWeights.word(
				new WordStats(ID, daysAgo(60), KnowledgeStatus.LEARNING, null, 0, false, null, null, null), NOW);
		assertThat(word.fresh()).isTrue();
		assertThat(word.weakness()).isEqualTo(0.5);
		assertThat(word.staleness()).isEqualTo(1);
		assertThat(word.dueness()).isEqualTo(0.5);
	}

	@ParameterizedTest(name = "ease {0}, lapses {1}, learning {2} → {3}")
	@CsvSource({ "2.50, 0, false, 0.0", "1.30, 0, false, 0.4", "1.90, 0, false, 0.2", "2.50, 2, false, 0.15",
			"2.50, 9, false, 0.3", "2.50, 0, true, 0.3", "1.30, 4, true, 1.0" })
	void wordWeaknessComesFromEaseLapsesAndRelearning(double ease, int lapses, boolean learning, double expected) {
		PracticeCandidate word = PracticeWeights.word(reviewedWord(ease, lapses, learning, daysAgo(3), NOW), NOW);
		assertThat(word.weakness()).isCloseTo(expected, within(1e-9));
		assertThat(word.fresh()).isFalse();
	}

	@Test
	void masteryHalvesWeakness() {
		WordStats mastered = new WordStats(ID, daysAgo(60), KnowledgeStatus.MASTERED, 1.3, 4, true, daysAgo(1), NOW,
				null);
		assertThat(PracticeWeights.word(mastered, NOW).weakness()).isEqualTo(0.5);
		TopicStats topic = new TopicStats(ID, daysAgo(60), KnowledgeStatus.MASTERED, 10, 0.4, daysAgo(1));
		assertThat(PracticeWeights.topic(topic, NOW).weakness()).isCloseTo(0.3, within(1e-9));
	}

	@ParameterizedTest(name = "due in {0} days → {1}")
	@CsvSource({ "-3, 1.0", "0, 1.0", "3.5, 0.5", "7, 0.0", "20, 0.0" })
	void dueWordsAreMostDue(double daysAhead, double expected) {
		Instant due = NOW.plus(Duration.ofMinutes(Math.round(daysAhead * 24 * 60)));
		assertThat(PracticeWeights.word(reviewedWord(2.5, 0, false, daysAgo(1), due), NOW).dueness())
			.isCloseTo(expected, within(1e-9));
	}

	@ParameterizedTest(name = "practised {0} days ago → {1}")
	@CsvSource({ "0, 0.0", "15, 0.5", "30, 1.0", "90, 1.0" })
	void stalenessGrowsOverAMonth(double days, double expected) {
		WordStats word = new WordStats(ID, daysAgo(120), KnowledgeStatus.LEARNING, 2.5, 0, false, daysAgo(days + 50),
				null, daysAgo(days));
		assertThat(PracticeWeights.word(word, NOW).staleness()).isCloseTo(expected, within(1e-9));
	}

	@Test
	void recentlyAddedMaterialIsFresh() {
		WordStats week = new WordStats(ID, daysAgo(6.9), KnowledgeStatus.LEARNING, 2.5, 0, false, daysAgo(1), null,
				null);
		assertThat(PracticeWeights.word(week, NOW).fresh()).isTrue();
		WordStats older = new WordStats(ID, daysAgo(7.1), KnowledgeStatus.LEARNING, 2.5, 0, false, daysAgo(1), null,
				null);
		assertThat(PracticeWeights.word(older, NOW).fresh()).isFalse();
	}

	@Test
	void topicWeaknessIsItsRecentErrorRate() {
		TopicStats untried = new TopicStats(ID, daysAgo(30), KnowledgeStatus.LEARNING, 0, null, null);
		PracticeCandidate fresh = PracticeWeights.topic(untried, NOW);
		assertThat(fresh.weakness()).isEqualTo(0.5);
		assertThat(fresh.fresh()).isTrue();

		TopicStats practised = new TopicStats(ID, daysAgo(30), KnowledgeStatus.LEARNING, 12, 0.75, daysAgo(15));
		PracticeCandidate topic = PracticeWeights.topic(practised, NOW);
		assertThat(topic.weakness()).isCloseTo(0.25, within(1e-9));
		assertThat(topic.staleness()).isCloseTo(0.5, within(1e-9));
		assertThat(topic.dueness()).isEqualTo(topic.staleness());
		assertThat(topic.fresh()).isFalse();
	}

}
