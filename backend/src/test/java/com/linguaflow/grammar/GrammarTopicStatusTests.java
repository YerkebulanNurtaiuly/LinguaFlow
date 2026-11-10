package com.linguaflow.grammar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import com.linguaflow.common.domain.KnowledgeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The knowledge-status lifecycle of a grammar topic (GRM-02), on the entity alone. */
class GrammarTopicStatusTests {

	private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");

	private static GrammarTopic topic(KnowledgeStatus initial) {
		return new GrammarTopic(UUID.randomUUID(), "de", "Dativ", "dativ", initial, T0);
	}

	private static String history(GrammarTopic topic) {
		return String.join(", ", topic.getStatusHistory()
			.stream()
			.map(c -> (c.getFromStatus() == null ? "-" : c.getFromStatus()) + ">" + c.getToStatus() + ":"
					+ c.getReason())
			.toList());
	}

	@ParameterizedTest
	@EnumSource(value = KnowledgeStatus.class, names = "MASTERED", mode = EnumSource.Mode.EXCLUDE)
	void addingATopicRecordsItsStartAndNeverMastersIt(KnowledgeStatus initial) {
		GrammarTopic topic = topic(initial);
		assertThat(topic.getStatus()).isEqualTo(initial).isNotEqualTo(KnowledgeStatus.MASTERED);
		assertThat(history(topic)).isEqualTo("->" + initial + ":CREATED");
	}

	@Test
	void aTopicCannotStartAsMastered() {
		assertThatThrownBy(() -> topic(KnowledgeStatus.MASTERED)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void theLearnerMovesBetweenLearningAndLearnedFreely() {
		GrammarTopic topic = topic(KnowledgeStatus.NEW);
		topic.changeStatusByUser(KnowledgeStatus.LEARNED, false, T0.plusSeconds(1));
		topic.changeStatusByUser(KnowledgeStatus.LEARNING, false, T0.plusSeconds(2));
		topic.changeStatusByUser(KnowledgeStatus.LEARNING, false, T0.plusSeconds(3));

		assertThat(history(topic)).isEqualTo("->NEW:CREATED, NEW>LEARNED:MANUAL, LEARNED>LEARNING:MANUAL");
	}

	@Test
	void masteryByHandNeedsConfirmation() {
		GrammarTopic topic = topic(KnowledgeStatus.LEARNED);

		assertThatThrownBy(() -> topic.changeStatusByUser(KnowledgeStatus.MASTERED, false, T0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("confirmMastered");
		assertThat(topic.getStatus()).isEqualTo(KnowledgeStatus.LEARNED);

		topic.changeStatusByUser(KnowledgeStatus.MASTERED, true, T0.plusSeconds(1));
		assertThat(topic.getStatus()).isEqualTo(KnowledgeStatus.MASTERED);
		// The learner may also take it back
		topic.changeStatusByUser(KnowledgeStatus.LEARNING, false, T0.plusSeconds(2));
		assertThat(history(topic)).endsWith("LEARNED>MASTERED:MANUAL, MASTERED>LEARNING:MANUAL");
	}

	@Test
	void aStartedTopicCannotGoBackToNew() {
		GrammarTopic topic = topic(KnowledgeStatus.LEARNING);
		assertThatThrownBy(() -> topic.changeStatusByUser(KnowledgeStatus.NEW, false, T0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("NEW");
		// Choosing NEW while it is NEW is simply no change
		topic(KnowledgeStatus.NEW).changeStatusByUser(KnowledgeStatus.NEW, false, T0);
	}

	@Test
	void progressRulesGrantAndTakeBackMastery() {
		GrammarTopic topic = topic(KnowledgeStatus.LEARNED);
		topic.changeStatusByRule(KnowledgeStatus.MASTERED, StatusChangeReason.MASTERY_RULE, T0.plusSeconds(1));
		topic.changeStatusByRule(KnowledgeStatus.LEARNED, StatusChangeReason.DEMOTION_RULE, T0.plusSeconds(2));

		assertThat(history(topic))
			.isEqualTo("->LEARNED:CREATED, LEARNED>MASTERED:MASTERY_RULE, MASTERED>LEARNED:DEMOTION_RULE");
	}

	@Test
	void eachRuleOnlyMovesItsOwnWay() {
		GrammarTopic topic = topic(KnowledgeStatus.LEARNING);
		assertThatThrownBy(
				() -> topic.changeStatusByRule(KnowledgeStatus.LEARNED, StatusChangeReason.MASTERY_RULE, T0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> topic.changeStatusByRule(KnowledgeStatus.MASTERED, StatusChangeReason.DEMOTION_RULE, T0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> topic.changeStatusByRule(KnowledgeStatus.LEARNED, StatusChangeReason.MANUAL, T0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(topic.getStatusHistory()).hasSize(1);
	}

}
