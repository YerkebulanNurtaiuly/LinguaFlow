package com.linguaflow.exercise;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.common.persistence.AuditableEntity;
import com.linguaflow.common.persistence.OwnedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One exercise in a set (EX-01). Its type decides the shape of the JSON {@code prompt} and
 * {@code solution} ({@link ExerciseFormat}); read them typed with {@link #content()}. The
 * grammar topics and words it practises are plain IDs in the link tables, since they live
 * in other modules; deleting one removes the link and keeps the exercise.
 */
@Entity
@Table(name = "exercises")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Exercise extends AuditableEntity implements OwnedEntity {

	/** Longest report reason kept (EX-07). */
	public static final int MAX_REPORT_REASON = 1000;

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "exercise_set_id", nullable = false, updatable = false)
	private UUID exerciseSetId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(nullable = false, updatable = false)
	private short position;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private ExerciseType type;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, updatable = false)
	private Map<String, Object> prompt;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, updatable = false)
	private Map<String, Object> solution;

	@Enumerated(EnumType.STRING)
	@Column(updatable = false)
	private @Nullable ExerciseDifficulty difficulty;

	/** Set when the learner reports the exercise as bad (EX-07). */
	@Column(name = "reported_at")
	private @Nullable Instant reportedAt;

	@Column(name = "report_reason")
	private @Nullable String reportReason;

	/** The AI call that generated it, for prompt tuning (AI-05's log); a plain ID. */
	@Column(name = "ai_request_id", updatable = false)
	private @Nullable UUID aiRequestId;

	@ElementCollection
	@CollectionTable(name = "exercise_grammar_topics", joinColumns = @JoinColumn(name = "exercise_id"))
	@Column(name = "grammar_topic_id", nullable = false)
	@BatchSize(size = 100)
	private Set<UUID> grammarTopicIds = new LinkedHashSet<>();

	@ElementCollection
	@CollectionTable(name = "exercise_vocabulary", joinColumns = @JoinColumn(name = "exercise_id"))
	@Column(name = "vocabulary_item_id", nullable = false)
	@BatchSize(size = 100)
	private Set<UUID> vocabularyItemIds = new LinkedHashSet<>();

	/**
	 * A new exercise at {@code position} in {@code set}.
	 * @throws IllegalArgumentException when the content breaks {@link ExerciseRules}
	 */
	public Exercise(ExerciseSet set, int position, ExerciseContent content, @Nullable ExerciseDifficulty difficulty,
			Collection<UUID> grammarTopicIds, Collection<UUID> vocabularyItemIds, @Nullable UUID aiRequestId) {
		Objects.requireNonNull(set.getId(), "the set must be saved first");
		if (position < 0 || position > Short.MAX_VALUE) {
			throw new IllegalArgumentException("position out of range: " + position);
		}
		List<String> problems = ExerciseRules.problems(content);
		if (!problems.isEmpty()) {
			throw new IllegalArgumentException("Inconsistent " + content.type() + " exercise: " + problems);
		}
		this.exerciseSetId = set.getId();
		this.userId = set.getUserId();
		this.position = (short) position;
		this.type = content.type();
		this.prompt = content.promptJson();
		this.solution = content.solutionJson();
		this.difficulty = difficulty;
		this.aiRequestId = aiRequestId;
		this.grammarTopicIds.addAll(grammarTopicIds);
		this.vocabularyItemIds.addAll(vocabularyItemIds);
	}

	/** The typed prompt and solution. */
	public ExerciseContent content() {
		return ExerciseContent.fromJson(type, prompt, solution);
	}

	public Set<UUID> getGrammarTopicIds() {
		return Set.copyOf(grammarTopicIds);
	}

	public Set<UUID> getVocabularyItemIds() {
		return Set.copyOf(vocabularyItemIds);
	}

	/** The live collections, for loading them inside a transaction. */
	Set<UUID> getGrammarTopicIdsForLoading() {
		return grammarTopicIds;
	}

	Set<UUID> getVocabularyItemIdsForLoading() {
		return vocabularyItemIds;
	}

	public boolean isReported() {
		return reportedAt != null;
	}

	/**
	 * Flags the exercise as bad (EX-07), so it's left out of future sets. Reporting again
	 * keeps the first time and updates the reason.
	 */
	public void report(@Nullable String reason, Instant at) {
		String trimmed = reason == null ? null : reason.strip();
		if (trimmed != null && trimmed.length() > MAX_REPORT_REASON) {
			throw new IllegalArgumentException("report reason longer than " + MAX_REPORT_REASON);
		}
		if (reportedAt == null) {
			reportedAt = Objects.requireNonNull(at);
		}
		reportReason = trimmed == null || trimmed.isEmpty() ? null : trimmed;
	}

	/** Takes a report back (EX-07): the exercise counts as fine again. */
	public void withdrawReport() {
		reportedAt = null;
		reportReason = null;
	}

}
