package com.linguaflow.common.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Minimal security setup until AUTH-04 adds JWT: the health endpoint is public and
 * everything else requires authentication. Security errors raised in the filter chain
 * are handed to the MVC exception resolver so they get the same ProblemDetail body as
 * every other error (see GlobalExceptionHandler).
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
		http
			.cors(Customizer.withDefaults())
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				.anyRequest().authenticated())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(errors -> errors
				.authenticationEntryPoint(
						(request, response, ex) -> exceptionResolver.resolveException(request, response, null, ex))
				.accessDeniedHandler(
						(request, response, ex) -> exceptionResolver.resolveException(request, response, null, ex)));
		return http.build();
	}

}
