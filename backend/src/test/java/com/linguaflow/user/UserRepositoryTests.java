package com.linguaflow.user;

import java.time.Instant;
import java.util.List;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.common.config.ClockConfig;
import com.linguaflow.common.config.JpaAuditingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, JpaAuditingConfig.class, ClockConfig.class })
@ActiveProfiles("test")
class UserRepositoryTests {

	@Autowired
	private UserRepository users;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void savesWithDefaultsAndAuditTimestamps() {
		User saved = users.saveAndFlush(new User("  Anna@Example.de ", "{bcrypt}hash", " Anna "));

		User loaded = users.findById(saved.getId()).orElseThrow();
		assertThat(loaded.getId().version()).isEqualTo(7);
		assertThat(loaded.getEmail()).isEqualTo("Anna@Example.de");
		assertThat(loaded.getDisplayName()).isEqualTo("Anna");
		assertThat(loaded.getRole()).isEqualTo(Role.USER);
		assertThat(loaded.isEnabled()).isTrue();
		assertThat(loaded.getTimezone()).isEqualTo("UTC");
		assertThat(loaded.getInterfaceLanguageCode()).isEqualTo("en");
		assertThat(loaded.getEmailVerifiedAt()).isNull();
		assertThat(loaded.getCreatedAt()).isNotNull();
		assertThat(loaded.getUpdatedAt()).isNotNull();
	}

	@Test
	void findsByEmailIgnoringCase() {
		users.saveAndFlush(new User("Anna@Example.de", "{bcrypt}hash", "Anna"));

		assertThat(users.findByEmail("anna@example.DE")).get().extracting(User::getDisplayName).isEqualTo("Anna");
		assertThat(users.existsByEmail("ANNA@EXAMPLE.DE")).isTrue();
		assertThat(users.findByEmail("someone@else.de")).isEmpty();
		assertThat(users.existsByEmail("someone@else.de")).isFalse();
	}

	@Test
	void rejectsSameEmailWithDifferentCase() {
		users.saveAndFlush(new User("ben@example.de", "{bcrypt}hash", "Ben"));
		assertThatThrownBy(() -> users.saveAndFlush(new User("Ben@Example.de", "{bcrypt}hash", "Ben 2")))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("users_email_lower_uq");
	}

	@Test
	void emailLookupUsesTheCaseInsensitiveIndex() {
		jdbc.execute("set local enable_seqscan = off");
		List<String> plan = jdbc.queryForList(
				"explain select * from users where lower(email) = lower('anna@example.de')", String.class);
		assertThat(String.join("\n", plan)).contains("users_email_lower_uq");
	}

	@Test
	void persistsProfileChanges() {
		User user = users.saveAndFlush(new User("clara@example.de", "{bcrypt}hash", "Clara"));
		Instant login = Instant.parse("2026-11-09T08:00:00Z");

		user.changeTimezone(java.time.ZoneId.of("Europe/Berlin"));
		user.recordLogin(login);
		user.promoteTo(Role.ADMIN);
		user.disable();
		users.saveAndFlush(user);

		User loaded = users.findById(user.getId()).orElseThrow();
		assertThat(loaded.zoneId()).isEqualTo(java.time.ZoneId.of("Europe/Berlin"));
		assertThat(loaded.getLastLoginAt()).isEqualTo(login);
		assertThat(loaded.getRole()).isEqualTo(Role.ADMIN);
		assertThat(loaded.isEnabled()).isFalse();
	}

}
