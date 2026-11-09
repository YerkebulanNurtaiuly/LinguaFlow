package com.linguaflow.grammar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.common.persistence.AuditableEntity;
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
public class GrammarTopic extends AuditableEntity {

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
	@OrderBy("changedAt")
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

	/** Changes the status and records it; a change to the current status is a no-op. */
	public void changeStatus(KnowledgeStatus newStatus, StatusChangeReason reason, Instant now) {
		if (newStatus == status) {
			return;
		}
		statusHistory.add(new GrammarTopicStatusChange(this, status, newStatus, reason, now));
		this.status = newStatus;
	}

	public void setAiExplanation(GrammarExplanation explanation, Instant generatedAt) {
		this.aiExplanation = explanation;
		this.aiExplanationGeneratedAt = generatedAt;
	}

}
