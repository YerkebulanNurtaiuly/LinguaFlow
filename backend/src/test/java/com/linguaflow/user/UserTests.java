package com.linguaflow.user;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTests {

	@Test
	void rejectsBlankRequiredFields() {
		assertThatThrownBy(() -> new User(" ", "{bcrypt}h", "Anna")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("email");
		assertThatThrownBy(() -> new User("a@b.de", "", "Anna")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("passwordHash");
		assertThatThrownBy(() -> new User("a@b.de", "{bcrypt}h", null)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void changingEmailResetsVerification() {
		User user = new User("a@b.de", "{bcrypt}h", "Anna");
		user.markEmailVerified(Instant.parse("2026-11-09T08:00:00Z"));

		user.changeEmail("new@b.de");

		assertThat(user.getEmail()).isEqualTo("new@b.de");
		assertThat(user.getEmailVerifiedAt()).isNull();
	}

}
