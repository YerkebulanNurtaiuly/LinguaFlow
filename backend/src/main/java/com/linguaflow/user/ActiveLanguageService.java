package com.linguaflow.user;

import java.util.UUID;

import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which target language the user is currently studying. Learning modules use
 * {@link #requireActive} to scope new and listed material to (user, language).
 */
@Service
public class ActiveLanguageService {

	private final UserLanguageRepository userLanguages;

	ActiveLanguageService(UserLanguageRepository userLanguages) {
		this.userLanguages = userLanguages;
	}

	/**
	 * @return the code of the user's active target language
	 * @throws ApiException NO_ACTIVE_LANGUAGE (409) if the user has not set one up yet
	 */
	@Transactional(readOnly = true)
	public String requireActive(UUID userId) {
		return userLanguages.findActive(userId)
			.map(UserLanguage::getLanguageCode)
			.orElseThrow(() -> new ApiException(ErrorCode.NO_ACTIVE_LANGUAGE,
					"Choose a language to learn first (POST /api/users/me/languages)"));
	}

	/** Makes {@code languageCode} the only active language of the user. */
	@Transactional
	void activate(UUID userId, String languageCode) {
		userLanguages.deactivateAll(userId);
		userLanguages.findById(new UserLanguage.Key(userId, languageCode)).orElseThrow().setActive(true);
	}

}
