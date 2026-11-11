package com.linguaflow.flashcard;

import java.util.Objects;
import java.util.UUID;

import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.persistence.AuditableEntity;
import com.linguaflow.common.persistence.OwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * One direction of one word to review (FC-01). Its front and back aren't stored: they're
 * built from the word whenever the card is read ({@link FlashcardContent}), so editing the
 * word updates its cards (schema.md Q3).
 */
@Entity
@Table(name = "flashcards")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Flashcard extends AuditableEntity implements OwnedEntity {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	/** The word, in the vocabulary module; a plain ID, not a JPA relation. */
	@Column(name = "vocabulary_item_id", nullable = false, updatable = false)
	private UUID vocabularyItemId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private CardDirection direction;

	/** Left out of reviews (FC-02). */
	@Setter
	@Column(nullable = false)
	private boolean suspended;

	public Flashcard(UUID userId, UUID vocabularyItemId, CardDirection direction) {
		this.userId = Objects.requireNonNull(userId);
		this.vocabularyItemId = Objects.requireNonNull(vocabularyItemId);
		this.direction = Objects.requireNonNull(direction);
	}

}
