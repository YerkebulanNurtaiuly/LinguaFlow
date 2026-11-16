package com.linguaflow.common.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Accepts the database as one {@code DATABASE_URL} in libpq form,
 * {@code postgresql://user:password@host:port/database?sslmode=require}, which is how
 * hosting platforms such as Render hand out a managed database (OPS-02). It's turned into
 * the {@code DB_URL} (JDBC), {@code DB_USERNAME} and {@code DB_PASSWORD} the app reads.
 * <p>
 * Explicitly set {@code DB_*} variables always win: the derived values are added with the
 * lowest precedence, and only when {@code DB_URL} isn't set at all.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

	static final String SOURCE_NAME = "databaseUrl";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		String databaseUrl = environment.getProperty("DATABASE_URL");
		if (databaseUrl == null || databaseUrl.isBlank() || environment.containsProperty("DB_URL")) {
			return;
		}
		environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, toDbProperties(databaseUrl)));
	}

	/** {@code DB_URL}, {@code DB_USERNAME} and {@code DB_PASSWORD} for a libpq URL. */
	static Map<String, Object> toDbProperties(String databaseUrl) {
		URI uri = URI.create(databaseUrl.strip());
		if (!"postgres".equals(uri.getScheme()) && !"postgresql".equals(uri.getScheme())) {
			throw new IllegalArgumentException("DATABASE_URL must start with postgresql:// or postgres://");
		}
		if (uri.getHost() == null || uri.getPath() == null || uri.getPath().length() < 2) {
			throw new IllegalArgumentException("DATABASE_URL needs a host and a database name");
		}
		StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
		if (uri.getPort() > 0) {
			jdbc.append(':').append(uri.getPort());
		}
		jdbc.append(uri.getRawPath());
		if (uri.getRawQuery() != null) {
			jdbc.append('?').append(uri.getRawQuery());
		}
		Map<String, Object> properties = new HashMap<>();
		properties.put("DB_URL", jdbc.toString());
		String userInfo = uri.getRawUserInfo();
		if (userInfo != null) {
			int colon = userInfo.indexOf(':');
			properties.put("DB_USERNAME", decode(colon < 0 ? userInfo : userInfo.substring(0, colon)));
			if (colon >= 0) {
				properties.put("DB_PASSWORD", decode(userInfo.substring(colon + 1)));
			}
		}
		return properties;
	}

	private static String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

}
