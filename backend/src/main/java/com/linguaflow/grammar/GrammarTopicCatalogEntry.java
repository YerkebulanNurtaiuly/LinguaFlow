package com.linguaflow.grammar;

import java.util.UUID;

import com.linguaflow.common.domain.CefrLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Predefined grammar topic users can pick from (GRM-03). Seed data, read-only for the app. */
@Entity
@Immutable
@Table(name = "grammar_topic_catalog")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GrammarTopicCatalogEntry {

	@Id
	private UUID id;

	@Column(name = "language_code", nullable = false)
	private String languageCode;

	@Column(nullable = false)
	private String slug;

	@Column(nullable = false)
	private String title;

	@Column(nullable = false)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(name = "cefr_level", nullable = false)
	private CefrLevel cefrLevel;

}
