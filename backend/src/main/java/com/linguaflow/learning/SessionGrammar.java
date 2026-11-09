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

/** Links a grammar topic (by ID, owned by the grammar module) to a study session. */
@Entity
@Immutable
@Table(name = "session_grammar")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionGrammar {

	@EmbeddedId
	private Key id;

	@Column(name = "added_at", nullable = false)
	private Instant addedAt;

	public SessionGrammar(UUID studySessionId, UUID grammarTopicId, Instant addedAt) {
		this.id = new Key(studySessionId, grammarTopicId);
		this.addedAt = addedAt;
	}

	@Embeddable
	public record Key(@Column(name = "study_session_id") UUID studySessionId,
			@Column(name = "grammar_topic_id") UUID grammarTopicId) implements Serializable {
	}

}
