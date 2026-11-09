package com.linguaflow.vocabulary;

import java.util.Objects;
import java.util.UUID;

import com.linguaflow.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/** User-defined label for grouping vocabulary (VOC-03); names are unique ignoring case. */
@Entity
@Table(name = "tags")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tag extends AuditableEntity {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "language_code", nullable = false, updatable = false)
	private String languageCode;

	@Setter
	@Column(nullable = false)
	private String name;

	public Tag(UUID userId, String languageCode, String name) {
		this.userId = Objects.requireNonNull(userId);
		this.languageCode = Objects.requireNonNull(languageCode);
		this.name = Objects.requireNonNull(name);
	}

}
