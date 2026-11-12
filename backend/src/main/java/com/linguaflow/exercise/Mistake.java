package com.linguaflow.exercise;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.linguaflow.common.persistence.OwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

/**
 * One error in an attempt (EX-05), for frequent mistakes and weak points (PRG-03,
 * PRG-04). The error type is an {@link ErrorType} name for the AI judge's errors, or the
 * exercise type's {@code WRONG_…} kind for closed formats ({@link #WRONG_GAP},
 * {@link #WRONG_CHOICE}).
 */
@Entity
@Table(name = "mistakes")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Mistake implements OwnedEntity {

	/** A wrong fill-in-the-blank gap. */
	public static final String WRONG_GAP = "WRONG_GAP";

	/** A wrong multiple-choice option. */
	public static final String WRONG_CHOICE = "WRONG_CHOICE";

	/** Same as {@code mistakes_error_type_format_ck}. */
	private static final Pattern FORMAT = Pattern.compile("^[A-Z][A-Z_]*$");

	/** Longest excerpt and explanation kept. */
	static final int MAX_TEXT = 1000;

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "attempt_id", nullable = false, updatable = false)
	private UUID attemptId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "grammar_topic_id", updatable = false)
	private @Nullable UUID grammarTopicId;

	@Column(name = "vocabulary_item_id", updatable = false)
	private @Nullable UUID vocabularyItemId;

	@Column(name = "error_type", nullable = false, updatable = false)
	private String errorType;

	/** The wrong part of the answer. */
	@Column(updatable = false)
	private @Nullable String excerpt;

	@Column(updatable = false)
	private @Nullable String explanation;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public Mistake(ExerciseAttempt attempt, String errorType, @Nullable UUID grammarTopicId,
			@Nullable UUID vocabularyItemId, @Nullable String excerpt, @Nullable String explanation,
			Instant createdAt) {
		if (!FORMAT.matcher(errorType).matches()) {
			throw new IllegalArgumentException("Not an error type: " + errorType);
		}
		this.attemptId = Objects.requireNonNull(attempt.getId());
		this.userId = attempt.getUserId();
		this.errorType = errorType;
		this.grammarTopicId = grammarTopicId;
		this.vocabularyItemId = vocabularyItemId;
		this.excerpt = cut(excerpt);
		this.explanation = cut(explanation);
		this.createdAt = Objects.requireNonNull(createdAt);
	}

	private static @Nullable String cut(@Nullable String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
	}

}
