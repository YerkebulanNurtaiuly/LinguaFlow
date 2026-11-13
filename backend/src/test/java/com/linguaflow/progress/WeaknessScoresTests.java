package com.linguaflow.progress;

import java.time.Duration;
import java.time.Instant;

import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.progress.WeaknessScores.Reason;
import com.linguaflow.progress.WeaknessScores.ReasonCode;
import com.linguaflow.progress.WeaknessScores.TopicInput;
import com.linguaflow.progress.WeaknessScores.WordInput;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeaknessScoresTests {

	private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

	private static Instant daysAgo(int days) {
		return NOW.minus(Duration.ofDays(days));
	}

	private static TopicInput topic(KnowledgeStatus status, int answers, Double accuracy, Instant last) {
		return new TopicInput(status, answers, accuracy, last, null, 0);
	}

	@Test
	void aTopicWithLowAccuracyAndAFrequentMistake() {
		var weakness = WeaknessScores.topic(
				new TopicInput(KnowledgeStatus.LEARNING, 20, 0.55, daysAgo(3), "WRONG_CASE", 4), NOW);
		// error (20 × 0.45 + 1) / 22, staleness 3/30
		assertThat(weakness.score()).isEqualTo(0.366);
		assertThat(weakness.reasons()).containsExactly(new Reason(ReasonCode.LOW_ACCURACY, 0.55, null),
				new Reason(ReasonCode.FREQUENT_MISTAKE, 4, "WRONG_CASE"));
	}

	@Test
	void solidStaleAndMasteredTopics() {
		var solid = WeaknessScores.topic(topic(KnowledgeStatus.LEARNED, 20, 1.0, NOW), NOW);
		assertThat(solid.score()).isEqualTo(0.034);
		assertThat(solid.reasons()).isEmpty();

		var stale = WeaknessScores.topic(topic(KnowledgeStatus.LEARNED, 20, 0.9, daysAgo(45)), NOW);
		assertThat(stale.score()).isEqualTo(0.352);
		assertThat(stale.reasons()).containsExactly(new Reason(ReasonCode.NOT_PRACTISED_RECENTLY, 45, null));

		var mastered = WeaknessScores.topic(
				new TopicInput(KnowledgeStatus.MASTERED, 20, 0.55, daysAgo(3), "WRONG_CASE", 4), NOW);
		assertThat(mastered.score()).isEqualTo(0.183);
	}

	@Test
	void fewAnswersArePulledTowardsTheMiddle() {
		var twoWrong = WeaknessScores.topic(topic(KnowledgeStatus.LEARNING, 2, 0.0, NOW), NOW);
		var twentyWrong = WeaknessScores.topic(topic(KnowledgeStatus.LEARNING, 20, 0.0, NOW), NOW);
		assertThat(twoWrong.score()).isEqualTo(0.563);
		assertThat(twentyWrong.score()).isEqualTo(0.716);
		// Unknown accuracy, never practised
		assertThat(WeaknessScores.topic(topic(KnowledgeStatus.NEW, 0, null, null), NOW).score()).isEqualTo(0.625);
		// A single lucky mistake isn't a frequent one
		assertThat(WeaknessScores.topic(new TopicInput(KnowledgeStatus.LEARNING, 20, 0.9, NOW, "WRONG_CASE", 1),
				NOW).reasons()).isEmpty();
	}

	@Test
	void wordsCountLapsesToo() {
		var weakness = WeaknessScores.word(new WordInput(KnowledgeStatus.LEARNING, 10, 0.6, 3, daysAgo(20)), NOW);
		// 0.5 × 5/12 + 0.3 × 3/4 + 0.2 × 20/30
		assertThat(weakness.score()).isEqualTo(0.567);
		assertThat(weakness.reasons()).containsExactly(new Reason(ReasonCode.LOW_ACCURACY, 0.6, null),
				new Reason(ReasonCode.LAPSES, 3, null), new Reason(ReasonCode.NOT_PRACTISED_RECENTLY, 20, null));

		var solid = WeaknessScores.word(new WordInput(KnowledgeStatus.LEARNED, 20, 1.0, 0, NOW), NOW);
		assertThat(solid.score()).isEqualTo(0.023);
		assertThat(solid.reasons()).isEmpty();
		// Lapses count up to four
		assertThat(WeaknessScores.word(new WordInput(KnowledgeStatus.LEARNED, 20, 1.0, 9, NOW), NOW).score())
			.isEqualTo(0.323);
	}

}
