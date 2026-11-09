package com.linguaflow.user;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ConflictException;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.common.error.NotFoundException;
import com.linguaflow.user.UserLanguageRequests.AddLanguageRequest;
import com.linguaflow.user.UserLanguageRequests.UpdateLanguageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserLanguageService {

	private static final Logger log = LoggerFactory.getLogger(UserLanguageService.class);

	private final UserLanguageRepository userLanguages;

	private final LanguageRepository languages;

	private final ActiveLanguageService activeLanguages;

	UserLanguageService(UserLanguageRepository userLanguages, LanguageRepository languages,
			ActiveLanguageService activeLanguages) {
		this.userLanguages = userLanguages;
		this.languages = languages;
		this.activeLanguages = activeLanguages;
	}

	@Transactional(readOnly = true)
	public List<UserLanguage> list(UUID userId) {
		return userLanguages.findAllForUser(userId);
	}

	/** Learning settings of one of the user's languages (for the review queue, practice, ...). */
	@Transactional(readOnly = true)
	public LearningSettings settingsFor(UUID userId, String languageCode) {
		return userLanguages.findById(new UserLanguage.Key(userId, languageCode))
			.map(UserLanguage::settings)
			.orElseThrow(UserLanguageService::notLearning);
	}

	@Transactional(noRollbackFor = ApiException.class)
	public UserLanguage add(UUID userId, AddLanguageRequest request) {
		Language target = languages.findById(request.languageCode())
			.filter(Language::isSupportedAsTarget)
			.orElseThrow(() -> new InvalidFieldException("languageCode", "cannot be learned yet"));
		requireExplanationLanguage(request.explanationLanguageCode(), target.getCode());
		UserLanguage.Key key = new UserLanguage.Key(userId, target.getCode());
		if (userLanguages.existsById(key)) {
			throw new ConflictException("You are already learning " + target.getName());
		}
		boolean first = userLanguages.findAllForUser(userId).isEmpty();
		UserLanguage added = new UserLanguage(userId, target.getCode(), request.explanationLanguageCode());
		added.setSelfAssessedLevel(request.selfAssessedLevel());
		userLanguages.save(added);
		if (first || Boolean.TRUE.equals(request.active())) {
			activeLanguages.activate(userId, target.getCode());
		}
		return reload(key);
	}

	@Transactional(noRollbackFor = ApiException.class)
	public UserLanguage update(UUID userId, String languageCode, UpdateLanguageRequest request) {
		UserLanguage.Key key = new UserLanguage.Key(userId, languageCode);
		UserLanguage language = userLanguages.findById(key).orElseThrow(UserLanguageService::notLearning);
		if (request.explanationLanguageCode() != null) {
			requireExplanationLanguage(request.explanationLanguageCode(), languageCode);
			language.setExplanationLanguageCode(request.explanationLanguageCode());
		}
		if (request.selfAssessedLevel() != null) {
			language.setSelfAssessedLevel(request.selfAssessedLevel());
		}
		if (request.settings() != null) {
			var settings = request.settings();
			language.updateSettings(settings.dailyReviewGoal(), settings.newCardsPerDay(),
					settings.preferredExerciseTypes(), settings.practiceSessionLength());
		}
		if (Boolean.FALSE.equals(request.active())) {
			throw new InvalidFieldException("active", "activate another language instead");
		}
		if (Boolean.TRUE.equals(request.active()) && !language.isActive()) {
			activeLanguages.activate(userId, languageCode);
		}
		return reload(key);
	}

	/**
	 * Removes the language and, through the database cascades, ALL learning data for it
	 * (vocabulary, grammar, sessions, reviews, progress). If it was active, the most
	 * recently added remaining language becomes active.
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public void remove(UUID userId, String languageCode) {
		UserLanguage language = userLanguages.findById(new UserLanguage.Key(userId, languageCode))
			.orElseThrow(UserLanguageService::notLearning);
		boolean wasActive = language.isActive();
		userLanguages.delete(language);
		log.info("User {} removed language {} and its learning data", userId, languageCode);
		if (wasActive) {
			userLanguages.findAllForUser(userId)
				.stream()
				.filter(other -> !other.getLanguageCode().equals(languageCode))
				.max(Comparator.comparing(UserLanguage::getCreatedAt))
				.ifPresent(next -> activeLanguages.activate(userId, next.getLanguageCode()));
		}
	}

	private void requireExplanationLanguage(String code, String targetCode) {
		if (code.equals(targetCode)) {
			throw new InvalidFieldException("explanationLanguageCode", "must differ from the language you learn");
		}
		if (!languages.findById(code).map(Language::isSupportedAsExplanation).orElse(false)) {
			throw new InvalidFieldException("explanationLanguageCode", "is not supported for explanations");
		}
	}

	private UserLanguage reload(UserLanguage.Key key) {
		return userLanguages.findById(key).orElseThrow();
	}

	private static NotFoundException notLearning() {
		return new NotFoundException("Resource not found");
	}

}
