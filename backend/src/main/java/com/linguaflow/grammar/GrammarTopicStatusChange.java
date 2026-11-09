package com.linguaflow.grammar;

import java.time.Instant;
import java.util.UUID;

import com.linguaflow.common.domain.KnowledgeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/** One entry of a grammar topic's status history (GRM-02). Append-only. */
@Entity
@Immutable
@Table(name = "grammar_topic_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GrammarTopicStatusChange {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "grammar_topic_id", nullable = false, updatable = false)
	private GrammarTopic topic;

	@Enumerated(EnumType.STRING)
	@Column(name = "from_status")
	private KnowledgeStatus fromStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "to_status", nullable = false)
	private KnowledgeStatus toStatus;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private StatusChangeReason reason;

	@Column(name = "changed_at", nullable = false)
	private Instant changedAt;

	GrammarTopicStatusChange(GrammarTopic topic, KnowledgeStatus fromStatus, KnowledgeStatus toStatus,
			StatusChangeReason reason, Instant changedAt) {
		this.topic = topic;
		this.fromStatus = fromStatus;
		this.toStatus = toStatus;
		this.reason = reason;
		this.changedAt = changedAt;
	}

}
