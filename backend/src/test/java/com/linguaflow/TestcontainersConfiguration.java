package com.linguaflow;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Wires the datasource to PostgreSQL 16 in Testcontainers. Import it in any test that
 * needs the database.
 * <p>
 * One container serves the whole test run (QA-01): it starts with the first context
 * and Testcontainers' Ryuk removes it when the JVM ends. Every Spring context still
 * gets a database of its own, created empty in that container, so contexts never see
 * each other's rows, exactly as when each had its own container.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	private static final AtomicInteger DATABASES = new AtomicInteger();

	@Bean
	JdbcConnectionDetails postgresConnectionDetails() {
		PostgreSQLContainer container = SharedPostgres.CONTAINER;
		String database = "test_" + DATABASES.incrementAndGet();
		try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(),
				container.getPassword()); Statement statement = connection.createStatement()) {
			statement.execute("create database " + database);
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Could not create test database " + database, ex);
		}
		String url = "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/" + database;
		return new JdbcConnectionDetails() {

			@Override
			public String getUsername() {
				return container.getUsername();
			}

			@Override
			public String getPassword() {
				return container.getPassword();
			}

			@Override
			public String getJdbcUrl() {
				return url;
			}

		};
	}

	/** Started on first use (class initialization is thread-safe), shared by every context. */
	private static final class SharedPostgres {

		@SuppressWarnings("resource") // stopped by Ryuk at JVM exit
		static final PostgreSQLContainer CONTAINER = start();

		private static PostgreSQLContainer start() {
			PostgreSQLContainer container = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
				// Cached contexts each keep a connection pool open
				.withCommand("postgres", "-c", "max_connections=500");
			container.start();
			return container;
		}

	}

}
