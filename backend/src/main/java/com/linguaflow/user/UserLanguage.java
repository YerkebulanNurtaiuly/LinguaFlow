package com.linguaflow.user;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.ExerciseType;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A user's learner profile for one target language: the scope of all their learning data
 * (every learning table references (user_id, language_code)), plus the per-language
 * learning settings (USR-03).
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

	@Column(name = "daily_review_goal", nullable = false)
	private int dailyReviewGoal = 50;

	@Column(name = "new_cards_per_day", nullable = false)
	private int newCardsPerDay = 20;

	/** Exercise type names; kept as strings in the entity, typed in {@link #settings()}. */
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "preferred_exercise_types", nullable = false)
	private List<String> preferredExerciseTypes = new ArrayList<>();

	@Column(name = "practice_session_length", nullable = false)
	private int practiceSessionLength = 20;

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

	public LearningSettings settings() {
		return new LearningSettings(dailyReviewGoal, newCardsPerDay,
				preferredExerciseTypes.stream().map(ExerciseType::valueOf).toList(), practiceSessionLength);
	}

	/** Applies the non-null values; ranges are validated by the request and the DB checks. */
	void updateSettings(Integer dailyReviewGoal, Integer newCardsPerDay, List<ExerciseType> preferredExerciseTypes,
			Integer practiceSessionLength) {
		if (dailyReviewGoal != null) {
			this.dailyReviewGoal = dailyReviewGoal;
		}
		if (newCardsPerDay != null) {
			this.newCardsPerDay = newCardsPerDay;
		}
		if (preferredExerciseTypes != null) {
			// Distinct, in a stable order
			this.preferredExerciseTypes = new ArrayList<>(
					preferredExerciseTypes.stream().distinct().sorted().map(ExerciseType::name).toList());
		}
		if (practiceSessionLength != null) {
			this.practiceSessionLength = practiceSessionLength;
		}
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
