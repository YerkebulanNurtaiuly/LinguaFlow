package com.linguaflow.common.config;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OPS-02: a platform's libpq DATABASE_URL becomes the JDBC settings the app reads. */
class DatabaseUrlEnvironmentPostProcessorTests {

	private final DatabaseUrlEnvironmentPostProcessor processor = new DatabaseUrlEnvironmentPostProcessor();

	private StandardEnvironment environment(Map<String, Object> variables) {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("test", variables));
		processor.postProcessEnvironment(environment, new SpringApplication());
		return environment;
	}

	@Test
	void aRenderStyleUrlBecomesJdbcSettings() {
		StandardEnvironment environment = environment(Map.of("DATABASE_URL",
				"postgresql://linguaflow_user:s3cr%40t%2Fpw@dpg-abc123-a:5432/linguaflow"));

		assertThat(environment.getProperty("DB_URL")).isEqualTo("jdbc:postgresql://dpg-abc123-a:5432/linguaflow");
		assertThat(environment.getProperty("DB_USERNAME")).isEqualTo("linguaflow_user");
		assertThat(environment.getProperty("DB_PASSWORD")).isEqualTo("s3cr@t/pw");
	}

	@Test
	void theQueryAndTheShortSchemeAreKept() {
		assertThat(DatabaseUrlEnvironmentPostProcessor
			.toDbProperties("postgres://u:p@db.example.com/app?sslmode=require")
			.get("DB_URL")).isEqualTo("jdbc:postgresql://db.example.com/app?sslmode=require");
	}

	@Test
	void explicitSettingsWin() {
		StandardEnvironment environment = environment(Map.of("DATABASE_URL", "postgresql://u:p@other:5432/x",
				"DB_URL", "jdbc:postgresql://mine:5432/mine"));

		assertThat(environment.getProperty("DB_URL")).isEqualTo("jdbc:postgresql://mine:5432/mine");
		assertThat(environment.getPropertySources().contains(DatabaseUrlEnvironmentPostProcessor.SOURCE_NAME))
			.isFalse();
	}

	@Test
	void withoutADatabaseUrlNothingChanges() {
		StandardEnvironment environment = environment(Map.of());

		assertThat(environment.containsProperty("DB_URL")).isFalse();
	}

	@Test
	void anUnusableUrlFailsClearly() {
		assertThatThrownBy(() -> DatabaseUrlEnvironmentPostProcessor.toDbProperties("mysql://u:p@h/db"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("postgresql://");
		assertThatThrownBy(() -> DatabaseUrlEnvironmentPostProcessor.toDbProperties("postgresql://u:p@host"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("database name");
	}

}
