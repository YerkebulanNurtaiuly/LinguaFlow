package com.linguaflow.vocabulary;

import java.util.UUID;

import com.linguaflow.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/** Example sentence for a vocabulary item; owned by (and only created through) the item. */
@Entity
@Table(name = "vocabulary_examples")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VocabularyExample extends AuditableEntity {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "vocabulary_item_id", nullable = false, updatable = false)
	private VocabularyItem item;

	@Setter
	@Column(nullable = false)
	private String sentence;

	@Setter
	private String translation;

	@Setter
	@Column(nullable = false)
	private short position;

	@Column(name = "ai_generated", nullable = false)
	private boolean aiGenerated;

	VocabularyExample(VocabularyItem item, String sentence, String translation, short position,
			boolean aiGenerated) {
		this.item = item;
		this.sentence = sentence;
		this.translation = translation;
		this.position = position;
		this.aiGenerated = aiGenerated;
	}

}
