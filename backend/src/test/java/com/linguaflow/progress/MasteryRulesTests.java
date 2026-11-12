package com.linguaflow.progress;

import java.util.Optional;

import com.linguaflow.common.config.LinguaFlowProperties.Mastery;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.progress.MasteryRules.TopicRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MasteryRulesTests {

	private static final Mastery DEFAULTS = new Mastery(null, null, null, null, null);

	@ParameterizedTest(name = "{0}, {1} attempts at {2} on {3} days → {4}")
	@CsvSource(nullValues = "none", value = {
			// Earning it: all three conditions
			"LEARNING, 20, 0.85, 3, MASTERED", "NEW, 40, 0.95, 10, MASTERED", "LEARNED, 25, 0.90, 4, MASTERED",
			"LEARNING, 19, 0.95, 5, none", "LEARNING, 30, 0.849, 5, none", "LEARNING, 30, 0.95, 2, none",
			// Keeping it, and losing it below 70%
			"MASTERED, 30, 0.75, 5, none", "MASTERED, 30, 0.70, 5, none", "MASTERED, 30, 0.69, 5, LEARNED",
			// Between the thresholds a LEARNED topic stays
			"LEARNED, 30, 0.75, 5, none" })
	void topics(KnowledgeStatus current, int attempts, double accuracy, int days, KnowledgeStatus expected) {
		assertThat(MasteryRules.topic(current, new TopicRecord(attempts, accuracy, days), DEFAULTS))
			.isEqualTo(Optional.ofNullable(expected));
	}

	@Test
	void aTopicWithoutAttemptsStaysAsItIs() {
		assertThat(MasteryRules.topic(KnowledgeStatus.MASTERED, new TopicRecord(0, null, 0), DEFAULTS)).isEmpty();
	}

	@ParameterizedTest(name = "{0} with a {1}-day interval → {2}")
	@CsvSource(nullValues = "none", value = { "LEARNING, 21, MASTERED", "NEW, 60, MASTERED", "LEARNED, 20, none",
			"MASTERED, 30, none", "MASTERED, 1, LEARNED" })
	void words(KnowledgeStatus current, int interval, KnowledgeStatus expected) {
		assertThat(MasteryRules.word(current, interval, DEFAULTS)).isEqualTo(Optional.ofNullable(expected));
	}

	@Test
	void thresholdsAreConfigurable() {
		Mastery strict = new Mastery(0.95, 30, 5, 0.80, 60);
		assertThat(MasteryRules.topic(KnowledgeStatus.LEARNING, new TopicRecord(25, 0.97, 6), strict)).isEmpty();
		assertThat(MasteryRules.topic(KnowledgeStatus.LEARNING, new TopicRecord(30, 0.97, 6), strict))
			.contains(KnowledgeStatus.MASTERED);
		assertThat(MasteryRules.topic(KnowledgeStatus.MASTERED, new TopicRecord(30, 0.79, 6), strict))
			.contains(KnowledgeStatus.LEARNED);
		assertThat(MasteryRules.word(KnowledgeStatus.LEARNING, 30, strict)).isEmpty();
		assertThatThrownBy(() -> new Mastery(0.80, null, null, 0.90, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
