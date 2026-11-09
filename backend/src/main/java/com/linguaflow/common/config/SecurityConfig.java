package com.linguaflow.common.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Minimal security setup until AUTH-04 adds JWT: the health endpoint (and the API docs
 * when enabled) are public and everything else requires authentication. Security errors raised in the filter chain
 * are handed to the MVC exception resolver so they get the same ProblemDetail body as
 * every other error (see GlobalExceptionHandler).
 */
@Configuration
public class SecurityConfig {

	private static final String[] AUTH_ENDPOINTS = { "/api/auth/register", "/api/auth/login", "/api/auth/refresh",
			"/api/auth/logout" };

	private static final String[] API_DOCS_PATHS = { "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html",
			"/swagger-ui/**" };

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
			@Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled) throws Exception {
		http
			.cors(Customizer.withDefaults())
			// Auth endpoints need no CSRF token: register/login carry no ambient credentials, and
			// refresh/logout rely on a SameSite=Strict cookie that browsers never attach to
			// cross-site requests. The strategy for the rest of the API is finalized in AUTH-04.
			.csrf(csrf -> csrf.ignoringRequestMatchers(authEndpointMatchers()))
			.authorizeHttpRequests(auth -> {
				auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
				auth.requestMatchers(HttpMethod.POST, AUTH_ENDPOINTS).permitAll();
				// Docs are public only where they are enabled (dev); elsewhere the paths stay locked
				if (apiDocsEnabled) {
					auth.requestMatchers(API_DOCS_PATHS).permitAll();
				}
				auth.anyRequest().authenticated();
			})
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(errors -> errors
				.authenticationEntryPoint(
						(request, response, ex) -> exceptionResolver.resolveException(request, response, null, ex))
				.accessDeniedHandler(
						(request, response, ex) -> exceptionResolver.resolveException(request, response, null, ex)));
		return http.build();
	}

	private static RequestMatcher[] authEndpointMatchers() {
		PathPatternRequestMatcher.Builder builder = PathPatternRequestMatcher.withDefaults();
		return java.util.Arrays.stream(AUTH_ENDPOINTS)
			.map(path -> builder.matcher(HttpMethod.POST, path))
			.toArray(RequestMatcher[]::new);
	}

}
