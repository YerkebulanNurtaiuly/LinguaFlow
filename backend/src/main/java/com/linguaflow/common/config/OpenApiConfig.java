package com.linguaflow.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata and the JWT bearer security scheme. All operations require the
 * bearer token by default; public endpoints opt out with
 * {@code @SecurityRequirements} (empty). Groups (one per module plus "all") are
 * declared in application.yml under {@code springdoc.group-configs}. Docs and Swagger UI
 * are only enabled in the dev profile.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OpenApiConfig {

	public static final String BEARER_SCHEME = "bearerAuth";

	@Bean
	OpenAPI linguaFlowOpenApi() {
		return new OpenAPI()
			.info(new Info().title("LinguaFlow API")
				.version("v1")
				.description("REST API of LinguaFlow, an AI-powered personalized language learning platform. "
						+ "Errors are returned as RFC 7807 application/problem+json with a stable `code` field.")
				.license(new License().name("MIT")))
			.components(new Components().addSecuritySchemes(BEARER_SCHEME,
					new SecurityScheme().type(SecurityScheme.Type.HTTP)
						.scheme("bearer")
						.bearerFormat("JWT")
						.description("Access token from POST /api/auth/login")))
			.addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
	}

}
