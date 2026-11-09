package com.linguaflow.common.config;

import com.linguaflow.common.security.JwtRoleConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Stateless JWT security (AUTH-04).
 * <ul>
 * <li>Every request except the public routes needs {@code Authorization: Bearer <access
 * token>}; the token is verified by the {@code JwtDecoder} from auth.JwtConfig
 * (signature, issuer, expiry). Its {@code role} claim becomes {@code ROLE_USER} /
 * {@code ROLE_ADMIN}.</li>
 * <li>Public: health, POST /api/auth/{register,login,refresh,logout,verify-email,
 * password-reset/request,password-reset}, and the API docs
 * where they are enabled (dev).</li>
 * <li>/api/admin/** requires ADMIN; method security ({@code @PreAuthorize}) is enabled for
 * finer rules.</li>
 * <li>CSRF is disabled: the API authenticates with a header that browsers never attach on
 * their own, and the only cookie (the refresh token) is SameSite=Strict and scoped to
 * /api/auth, so it is not sent on cross-site requests.</li>
 * <li>401/403 from the filter chain go through the MVC exception resolver, so they get the
 * same ProblemDetail body as every other error (GlobalExceptionHandler).</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

	private static final String[] PUBLIC_AUTH_ENDPOINTS = { "/api/auth/register", "/api/auth/login",
			"/api/auth/refresh", "/api/auth/logout", "/api/auth/verify-email", "/api/auth/password-reset/request",
			"/api/auth/password-reset" };

	private static final String[] API_DOCS_PATHS = { "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html",
			"/swagger-ui/**" };

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
			@Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled) throws Exception {
		AuthenticationEntryPoint unauthorized = (request, response, ex) -> exceptionResolver.resolveException(request,
				response, null, ex);
		AccessDeniedHandler forbidden = (request, response, ex) -> exceptionResolver.resolveException(request,
				response, null, ex);

		http.cors(Customizer.withDefaults())
			.csrf(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> {
				auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
				auth.requestMatchers(HttpMethod.POST, PUBLIC_AUTH_ENDPOINTS).permitAll();
				// Docs are public only where they are enabled (dev); elsewhere the paths stay locked
				if (apiDocsEnabled) {
					auth.requestMatchers(API_DOCS_PATHS).permitAll();
				}
				auth.requestMatchers("/api/admin/**").hasRole("ADMIN");
				auth.anyRequest().authenticated();
			})
			.oauth2ResourceServer(oauth -> oauth
				.jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtRoleConverter()))
				.authenticationEntryPoint(unauthorized)
				.accessDeniedHandler(forbidden))
			.exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden));
		return http.build();
	}

}
