package com.linguaflow.user;

import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ErrorCode;
import com.linguaflow.common.error.InvalidFieldException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileService {

	private static final Set<String> ZONES = ZoneId.getAvailableZoneIds();

	private final UserRepository users;

	private final LanguageRepository languages;

	UserProfileService(UserRepository users, LanguageRepository languages) {
		this.users = users;
		this.languages = languages;
	}

	@Transactional(readOnly = true)
	public User get(UUID userId) {
		return currentUser(userId);
	}

	@Transactional(noRollbackFor = ApiException.class)
	public User update(UUID userId, UpdateProfileRequest request) {
		User user = currentUser(userId);
		if (request.displayName() != null) {
			user.changeDisplayName(request.displayName());
		}
		if (request.interfaceLanguage() != null) {
			if (!languages.existsById(request.interfaceLanguage())) {
				throw new InvalidFieldException("interfaceLanguage", "is not a supported language");
			}
			user.changeInterfaceLanguage(request.interfaceLanguage());
		}
		if (request.timezone() != null) {
			// Region IDs only (e.g. Europe/Berlin), so daylight saving is handled; plus UTC
			if (!ZONES.contains(request.timezone()) && !"UTC".equals(request.timezone())) {
				throw new InvalidFieldException("timezone", "must be an IANA time zone such as Europe/Berlin");
			}
			user.changeTimezone(ZoneId.of(request.timezone()));
		}
		return user;
	}

	/**
	 * The user behind a valid access token. If the account no longer exists (deleted while
	 * the token was still valid), the token is treated as invalid: 401, so clients log out.
	 */
	public User currentUser(UUID userId) {
		return users.findById(userId)
			.orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "This account no longer exists"));
	}

}
