package com.linguaflow.auth.password;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Validator for {@link ValidPassword}; each rule reports its own message. */
public class PasswordPolicy implements ConstraintValidator<ValidPassword, String> {

	public static final int MIN_LENGTH = 10;

	/** BCrypt only uses the first 72 bytes; Spring Security rejects longer input. */
	public static final int MAX_BYTES = 72;

	// Small deny-list of the most common passwords that pass the length rule
	private static final Set<String> COMMON = Set.of("password123", "password1234", "1234567890", "12345678910",
			"qwertyuiop", "qwerty12345", "iloveyou123", "passwort123", "0123456789", "abcdefghij", "aaaaaaaaaa",
			"letmein1234", "welcome123", "admin12345", "linguaflow", "linguaflow123");

	@Override
	public boolean isValid(String password, ConstraintValidatorContext context) {
		if (password == null) {
			return true; // @NotBlank reports missing values
		}
		String violation = check(password);
		if (violation == null) {
			return true;
		}
		context.disableDefaultConstraintViolation();
		context.buildConstraintViolationWithTemplate(violation).addConstraintViolation();
		return false;
	}

	/** Returns the reason the password is rejected, or {@code null} if it is acceptable. */
	static String check(String password) {
		if (password.codePointCount(0, password.length()) < MIN_LENGTH) {
			return "must be at least " + MIN_LENGTH + " characters";
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
			return "must be at most " + MAX_BYTES + " bytes (about " + MAX_BYTES + " ASCII characters)";
		}
		if (password.isBlank()) {
			return "must not be only whitespace";
		}
		if (COMMON.contains(password.toLowerCase(Locale.ROOT))) {
			return "is too common";
		}
		return null;
	}

}
