package com.linguaflow.learning.practice;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ErrorCode;
import com.linguaflow.common.error.NotFoundException;
import com.linguaflow.learning.practice.MaterialSelector.Limits;
import com.linguaflow.learning.practice.MaterialSelector.Selection;
import com.linguaflow.user.ActiveLanguageService;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chooses the words and grammar topics for a practice session (PRC-01), for PRC-02.
 * It reads the active language's material ({@link PracticeCandidates}), scores it
 * ({@link PracticeWeights}) and picks with {@link MaterialSelector}. The seed is stored
 * with the practice session, so a selection can be reproduced while the material and
 * the clock agree.
 */
@Service
public class MaterialSelectionService {

	private final PracticeCandidates candidates;

	private final ActiveLanguageService activeLanguages;

	private final Clock clock;

	MaterialSelectionService(PracticeCandidates candidates, ActiveLanguageService activeLanguages, Clock clock) {
		this.candidates = candidates;
		this.activeLanguages = activeLanguages;
		this.clock = clock;
	}

	/**
	 * What to select.
	 * @param studySessionId CURRENT_LESSON only: the lesson; null means the latest
	 */
	public record Request(PracticeMode mode, @Nullable UUID studySessionId, Limits limits, long seed) {
	}

	/**
	 * The picks, and the lesson they came from (CURRENT_LESSON).
	 */
	public record Result(Selection selection, @Nullable UUID studySessionId) {

		public List<UUID> vocabularyItemIds() {
			return selection.vocabularyItemIds();
		}

		public List<UUID> grammarTopicIds() {
			return selection.grammarTopicIds();
		}

	}

	/**
	 * @throws ApiException 409 {@code NO_STUDY_SESSION} for CURRENT_LESSON without any
	 * lesson
	 * @throws NotFoundException when the chosen lesson isn't the user's in the active
	 * language
	 */
	@Transactional(readOnly = true)
	public Result select(UUID userId, Request request) {
		String language = activeLanguages.requireActive(userId);
		UUID lesson = null;
		if (request.mode() == PracticeMode.CURRENT_LESSON) {
			if (request.studySessionId() != null) {
				if (!candidates.ownsStudySession(userId, language, request.studySessionId())) {
					throw new NotFoundException("Resource not found");
				}
				lesson = request.studySessionId();
			}
			else {
				lesson = candidates.latestStudySession(userId, language)
					.orElseThrow(() -> new ApiException(ErrorCode.NO_STUDY_SESSION,
							"There's no study session yet to practise; add a lesson first."));
			}
		}
		Instant now = clock.instant();
		List<PracticeCandidate> words = candidates.words(userId, language, lesson)
			.stream()
			.map(word -> PracticeWeights.word(word, now))
			.toList();
		List<PracticeCandidate> topics = candidates.topics(userId, language, lesson)
			.stream()
			.map(topic -> PracticeWeights.topic(topic, now))
			.toList();
		return new Result(MaterialSelector.select(request.mode(), words, topics, request.limits(), request.seed()),
				lesson);
	}

}
