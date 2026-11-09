package com.linguaflow.common.config;

import java.time.Duration;
import java.util.List;

import com.linguaflow.common.logging.CorrelationIdFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS for browser clients served from another origin (the Vite dev server). In
 * deployments where nginx serves the frontend and proxies /api, requests are
 * same-origin and the allowed-origins list can stay empty.
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfig {

	@Bean
	CorsConfigurationSource corsConfigurationSource(LinguaFlowProperties properties) {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(properties.cors().allowedOrigins());
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("Authorization", "Content-Type", CorrelationIdFilter.HEADER));
		config.setExposedHeaders(List.of(CorrelationIdFilter.HEADER));
		// Needed for the HttpOnly refresh-token cookie (AUTH-03)
		config.setAllowCredentials(true);
		config.setMaxAge(Duration.ofHours(1));

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", config);
		return source;
	}

}
