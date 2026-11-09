package com.linguaflow.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** A language from the seeded {@code languages} table (ISO 639-1 code as key). Read-only. */
@Entity
@Immutable
@Table(name = "languages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Language {

	@Id
	private String code;

	@Column(nullable = false)
	private String name;

	@Column(name = "native_name", nullable = false)
	private String nativeName;

	@Column(name = "supported_as_target", nullable = false)
	private boolean supportedAsTarget;

	@Column(name = "supported_as_explanation", nullable = false)
	private boolean supportedAsExplanation;

}
