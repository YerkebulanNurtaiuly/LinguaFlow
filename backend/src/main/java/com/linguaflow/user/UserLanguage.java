package com.linguaflow.user;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A user's learner profile for one target language: the scope of all their learning data
 * (every learning table references (user_id, language_code)). Learning settings such as
 * daily goals are added in USR-03.
 */
@Entity
@Table(name = "user_languages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserLanguage extends AuditableEntity {

	@EmbeddedId
	private Key id;

	@Setter
	@Column(name = "explanation_language_code", nullable = false)
	private String explanationLanguageCode;

	@Setter
	@Enumerated(EnumType.STRING)
	@Column(name = "self_assessed_level")
	private CefrLevel selfAssessedLevel;

	@Column(nullable = false)
	private boolean active;

	public UserLanguage(UUID userId, String languageCode, String explanationLanguageCode) {
		this.id = new Key(userId, languageCode);
		this.explanationLanguageCode = Objects.requireNonNull(explanationLanguageCode);
	}

	public UUID getUserId() {
		return id.userId();
	}

	public String getLanguageCode() {
		return id.languageCode();
	}

	/** Use ActiveLanguageService to switch languages; it keeps "one active per user". */
	void setActive(boolean active) {
		this.active = active;
	}

	@Embeddable
	public record Key(@Column(name = "user_id") UUID userId, @Column(name = "language_code") String languageCode)
			implements Serializable {
	}

}
