package com.linguaflow.vocabulary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
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
 * A word or phrase a user is learning, scoped to (user, target language). Lemma
 * normalization for duplicate detection happens in the service layer (VOC-01).
 */
@Entity
@Table(name = "vocabulary_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VocabularyItem extends AuditableEntity implements OwnedEntity {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "language_code", nullable = false, updatable = false)
	private String languageCode;

	@Setter
	@Column(nullable = false)
	private String lemma;

	@Setter
	@Column(name = "normalized_lemma", nullable = false)
	private String normalizedLemma;

	@Setter
	@Enumerated(EnumType.STRING)
	@Column(name = "part_of_speech")
	private PartOfSpeech partOfSpeech;

	@Setter
	private String translation;

	@Setter
	@Enumerated(EnumType.STRING)
	private Gender gender;

	@Setter
	private String plural;

	/** Word-type specific data, e.g. verb forms {@code praeteritum, partizip2, auxiliary}. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private Map<String, Object> extra = new HashMap<>();

	@Setter
	@Enumerated(EnumType.STRING)
	@Column(name = "cefr_level")
	private CefrLevel cefrLevel;

	@Setter
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private KnowledgeStatus status = KnowledgeStatus.LEARNING;

	@Setter
	@Enumerated(EnumType.STRING)
	@Column(name = "ai_status", nullable = false)
	private AiStatus aiStatus = AiStatus.PENDING_AI;

	/** Fields whose current value came from AI; a user edit removes the field (VOC-05). */
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "ai_generated_fields", nullable = false)
	private List<String> aiGeneratedFields = new ArrayList<>();

	@Setter
	private String notes;

	@Version
	@Column(nullable = false)
	private long version;

	@OneToMany(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("position")
	private List<VocabularyExample> examples = new ArrayList<>();

	@ManyToMany
	@JoinTable(name = "vocabulary_item_tags", joinColumns = @JoinColumn(name = "vocabulary_item_id"),
			inverseJoinColumns = @JoinColumn(name = "tag_id"))
	private Set<Tag> tags = new HashSet<>();

	public VocabularyItem(UUID userId, String languageCode, String lemma, String normalizedLemma) {
		this.userId = Objects.requireNonNull(userId);
		this.languageCode = Objects.requireNonNull(languageCode);
		this.lemma = Objects.requireNonNull(lemma);
		this.normalizedLemma = Objects.requireNonNull(normalizedLemma);
	}

	/** Changes the lemma; the caller supplies its {@link LemmaNormalizer} key. */
	public void rename(String lemma, String normalizedLemma) {
		this.lemma = Objects.requireNonNull(lemma);
		this.normalizedLemma = Objects.requireNonNull(normalizedLemma);
	}

	/**
	 * Sets the knowledge status on the learner's request. MASTERED can't be chosen: it is
	 * earned through practice (PRG-02), which uses {@link #setStatus} directly.
	 */
	public void changeStatusByUser(KnowledgeStatus newStatus) {
		if (newStatus == KnowledgeStatus.MASTERED) {
			throw new IllegalArgumentException("MASTERED is earned through practice, not set by the user");
		}
		this.status = Objects.requireNonNull(newStatus);
	}

	/** The user changed {@code field}, so its value no longer counts as AI-generated (VOC-05: user edits win). */
	public void markEditedByUser(String field) {
		aiGeneratedFields.remove(field);
	}

	public void setExtra(Map<String, Object> extra) {
		this.extra = new HashMap<>(extra);
	}

	public void setAiGeneratedFields(List<String> fields) {
		this.aiGeneratedFields = new ArrayList<>(fields);
	}

	public VocabularyExample addExample(String sentence, String translation, boolean aiGenerated) {
		VocabularyExample example = new VocabularyExample(this, sentence, translation, (short) examples.size(),
				aiGenerated);
		examples.add(example);
		return example;
	}

	public void addTag(Tag tag) {
		tags.add(tag);
	}

	public void removeTag(Tag tag) {
		tags.remove(tag);
	}

}
