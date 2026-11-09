package com.linguaflow.auth.password;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTests {

	@ParameterizedTest
	@ValueSource(strings = { "correct-horse-battery", "Zehn Zeich", "äöüßäöüßäö", "a-very-long-passphrase-that-is-ok" })
	void acceptsGoodPasswords(String password) {
		assertThat(PasswordPolicy.check(password)).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "short", "123456789" })
	void rejectsShortPasswords(String password) {
		assertThat(PasswordPolicy.check(password)).contains("at least 10");
	}

	@ParameterizedTest
	@ValueSource(strings = { "Password123", "QWERTYUIOP", "linguaflow" })
	void rejectsCommonPasswordsIgnoringCase(String password) {
		assertThat(PasswordPolicy.check(password)).isEqualTo("is too common");
	}

	@ParameterizedTest
	@ValueSource(ints = { 72, 73 })
	void enforcesTheBcryptByteLimit(int length) {
		String password = "x".repeat(length);
		if (length <= PasswordPolicy.MAX_BYTES) {
			assertThat(PasswordPolicy.check(password)).isNull();
		}
		else {
			assertThat(PasswordPolicy.check(password)).contains("72 bytes");
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "          ", "\t\t\t\t\t\t\t\t\t\t" })
	void rejectsWhitespaceOnly(String password) {
		assertThat(PasswordPolicy.check(password)).isEqualTo("must not be only whitespace");
	}

}
