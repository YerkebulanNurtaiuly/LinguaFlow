package com.linguaflow.grammar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.common.persistence.AuditableEntity;
import com.linguaflow.common.persistence.OwnedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A grammar topic a user has studied, scoped to (user, target language). Every status
 * change is recorded in the history; a topic can never start as MASTERED.
 */
@Entity
@Table(name = "grammar_topics")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GrammarTopic extends AuditableEntity implements OwnedEntity {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "language_code", nullable = false, updatable = false)
	private String languageCode;

	/** Catalog entry the topic was picked from, if any (ID only; the catalog is read-only). */
	@Setter
	@Column(name = "catalog_topic_id")
	private UUID catalogTopicId;

	@Column(nullable = false)
	private String title;

	@Column(name = "normalized_title", nullable = false)
	private String normalizedTitle;

	@Setter
	private String description;

	@Setter
	@Column(name = "personal_notes")
	private String personalNotes;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "ai_explanation")
	private GrammarExplanation aiExplanation;

	@Column(name = "ai_explanation_generated_at")
	private Instant aiExplanationGeneratedAt;

	@Setter
	@Enumerated(EnumType.STRING)
	@Column(name = "cefr_level")
	private CefrLevel cefrLevel;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private KnowledgeStatus status;

	@Version
	@Column(nullable = false)
	private long version;

	@OneToMany(mappedBy = "topic", cascade = CascadeType.ALL, orphanRemoval = true)
	// IDs are UUIDv7, so they break ties between changes made in the same instant
	@OrderBy("changedAt, id")
	private List<GrammarTopicStatusChange> statusHistory = new ArrayList<>();

	public GrammarTopic(UUID userId, String languageCode, String title, String normalizedTitle,
			KnowledgeStatus initialStatus, Instant now) {
		if (initialStatus == KnowledgeStatus.MASTERED) {
			throw new IllegalArgumentException("A grammar topic cannot start as MASTERED; mastery must be earned");
		}
		this.userId = Objects.requireNonNull(userId);
		this.languageCode = Objects.requireNonNull(languageCode);
		rename(title, normalizedTitle);
		this.status = Objects.requireNonNull(initialStatus);
		statusHistory.add(new GrammarTopicStatusChange(this, null, initialStatus, StatusChangeReason.CREATED, now));
	}

	public void rename(String title, String normalizedTitle) {
		this.title = Objects.requireNonNull(title);
		this.normalizedTitle = Objects.requireNonNull(normalizedTitle);
	}

	/**
	 * The learner moves the topic (GRM-02). LEARNING and LEARNED can be chosen freely, in
	 * any direction. MASTERED is normally earned (PRG-02), so the learner can only claim it
	 * explicitly ({@code masteryConfirmed}). NEW only describes a topic not started yet, so
	 * it can't be chosen again. Choosing the current status changes nothing.
	 * @throws IllegalArgumentException with a message fragment when the change isn't allowed
	 */
	public void changeStatusByUser(KnowledgeStatus newStatus, boolean masteryConfirmed, Instant now) {
		Objects.requireNonNull(newStatus);
		if (newStatus == status) {
			return;
		}
		if (newStatus == KnowledgeStatus.NEW) {
			throw new IllegalArgumentException("can't be set back to NEW once the topic has been started");
		}
		if (newStatus == KnowledgeStatus.MASTERED && !masteryConfirmed) {
			throw new IllegalArgumentException(
					"can only be set to MASTERED with confirmMastered; mastery is normally earned through practice");
		}
		record(newStatus, StatusChangeReason.MANUAL, now);
	}

	/**
	 * A progress rule moves the topic (PRG-02): MASTERY_RULE grants MASTERED, DEMOTION_RULE
	 * takes a topic back to LEARNING or LEARNED. Choosing the current status changes nothing.
	 */
	public void changeStatusByRule(KnowledgeStatus newStatus, StatusChangeReason reason, Instant now) {
		Objects.requireNonNull(newStatus);
		boolean allowed = switch (reason) {
			case MASTERY_RULE -> newStatus == KnowledgeStatus.MASTERED;
			case DEMOTION_RULE -> newStatus == KnowledgeStatus.LEARNING || newStatus == KnowledgeStatus.LEARNED;
			case CREATED, MANUAL -> false;
		};
		if (!allowed) {
			throw new IllegalArgumentException(reason + " can't move a topic to " + newStatus);
		}
		if (newStatus != status) {
			record(newStatus, reason, now);
		}
	}

	private void record(KnowledgeStatus newStatus, StatusChangeReason reason, Instant now) {
		statusHistory.add(new GrammarTopicStatusChange(this, status, newStatus, reason, now));
		this.status = newStatus;
	}

	public void setAiExplanation(GrammarExplanation explanation, Instant generatedAt) {
		this.aiExplanation = explanation;
		this.aiExplanationGeneratedAt = generatedAt;
	}

}
