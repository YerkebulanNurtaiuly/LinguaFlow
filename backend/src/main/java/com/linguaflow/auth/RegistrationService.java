package com.linguaflow.auth;

import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ErrorCode;
import com.linguaflow.user.User;
import com.linguaflow.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class RegistrationService {

	private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

	private static final String EMAIL_UNIQUE_INDEX = "users_email_lower_uq";

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final AccountService accounts;

	RegistrationService(UserRepository users, PasswordEncoder passwordEncoder, AccountService accounts) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.accounts = accounts;
	}

	/**
	 * Creates an account with a hashed password.
	 * @throws ApiException with {@link ErrorCode#EMAIL_TAKEN} if the email (ignoring case)
	 * is already registered
	 */
	public User register(RegistrationRequest request) {
		if (users.existsByEmail(request.email().strip())) {
			throw emailTaken();
		}
		User user = new User(request.email(), passwordEncoder.encode(request.password()), request.displayName());
		try {
			user = users.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException ex) {
			// Two registrations for the same email raced past the exists check
			if (ex.getMessage() != null && ex.getMessage().contains(EMAIL_UNIQUE_INDEX)) {
				throw emailTaken();
			}
			throw ex;
		}
		log.info("Registered user {}", user.getId());
		accounts.sendVerificationEmail(user.getId());
		return user;
	}

	private static ApiException emailTaken() {
		return new ApiException(ErrorCode.EMAIL_TAKEN, "An account with this email already exists");
	}

}
