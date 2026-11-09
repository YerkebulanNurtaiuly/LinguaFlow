package com.linguaflow.learning;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.linguaflow.common.persistence.AuditableEntity;
import com.linguaflow.common.persistence.OwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * One lesson (e.g. "Lesson 5"), scoped to (user, target language). At most one session
 * per user and language is active; new material is attached to it (SES-01). Attached
 * material lives in {@link SessionVocabulary} / {@link SessionGrammar}.
 */
@Entity
@Table(name = "study_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudySession extends AuditableEntity implements OwnedEntity {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "language_code", nullable = false, updatable = false)
	private String languageCode;

	@Setter
	@Column(nullable = false)
	private String title;

	@Setter
	@Column(name = "studied_on", nullable = false)
	private LocalDate studiedOn;

	@Setter
	private String notes;

	@Setter
	@Column(nullable = false)
	private boolean active;

	@Version
	@Column(nullable = false)
	private long version;

	public StudySession(UUID userId, String languageCode, String title, LocalDate studiedOn) {
		this.userId = Objects.requireNonNull(userId);
		this.languageCode = Objects.requireNonNull(languageCode);
		this.title = Objects.requireNonNull(title);
		this.studiedOn = Objects.requireNonNull(studiedOn);
	}

}
