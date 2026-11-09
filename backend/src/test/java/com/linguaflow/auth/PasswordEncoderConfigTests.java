package com.linguaflow.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordEncoderConfigTests {

	private final PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder();

	@Test
	void hashesWithBcryptAndAlgorithmPrefix() {
		String hash = encoder.encode("correct horse battery staple");

		assertThat(hash).startsWith("{bcrypt}$2");
		assertThat(encoder.matches("correct horse battery staple", hash)).isTrue();
		assertThat(encoder.matches("wrong", hash)).isFalse();
	}

	@Test
	void samePasswordGetsDifferentSalts() {
		assertThat(encoder.encode("secret-password")).isNotEqualTo(encoder.encode("secret-password"));
	}

}
