package com.linguaflow.learning;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Links a vocabulary item (by ID, owned by the vocabulary module) to a study session. */
@Entity
@Immutable
@Table(name = "session_vocabulary")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionVocabulary {

	@EmbeddedId
	private Key id;

	@Column(name = "added_at", nullable = false)
	private Instant addedAt;

	public SessionVocabulary(UUID studySessionId, UUID vocabularyItemId, Instant addedAt) {
		this.id = new Key(studySessionId, vocabularyItemId);
		this.addedAt = addedAt;
	}

	@Embeddable
	public record Key(@Column(name = "study_session_id") UUID studySessionId,
			@Column(name = "vocabulary_item_id") UUID vocabularyItemId) implements Serializable {
	}

}
