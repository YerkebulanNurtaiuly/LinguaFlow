package com.linguaflow.common.config;

import java.util.Map;

import com.linguaflow.common.error.ApiProblem;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata and the JWT bearer security scheme. All operations require the
 * bearer token by default; public endpoints opt out with
 * {@code @SecurityRequirements} (empty). Groups (one per module plus "all") are
 * declared in application.yml under {@code springdoc.group-configs}. Docs and Swagger UI
 * are only enabled in the dev profile.
 * <p>
 * Every /api operation also documents the shared error responses using the
 * {@code ApiProblem} schema, which the frontend's generated types rely on (INF-09).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OpenApiConfig {

	public static final String BEARER_SCHEME = "bearerAuth";

	private static final String PROBLEM_SCHEMA = "ApiProblem";

	private static final String PROBLEM_MEDIA_TYPE = "application/problem+json";

	// Status -> description of the error responses added to every /api operation
	private static final Map<String, String> ERROR_RESPONSES = Map.of("400", "Invalid request",
			"401", "Missing or invalid access token", "403", "Not allowed", "404", "Resource not found", "500",
			"Unexpected server error");

	@Bean
	OpenAPI linguaFlowOpenApi() {
		return new OpenAPI()
			// Relative server: the frontend calls the API on its own origin (proxied), and the
			// committed spec snapshot stays identical regardless of host/port
			.addServersItem(new Server().url("/").description("Same origin"))
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

	@Bean
	GlobalOpenApiCustomizer problemResponsesCustomizer() {
		return openApi -> {
			Components components = openApi.getComponents();
			if (components == null) {
				components = new Components();
				openApi.setComponents(components);
			}
			ModelConverters.getInstance()
				.readAll(new AnnotatedType(ApiProblem.class))
				.forEach(components::addSchemas);

			if (openApi.getPaths() == null) {
				return;
			}
			openApi.getPaths().forEach((path, item) -> {
				if (!path.startsWith("/api/")) {
					return;
				}
				item.readOperations().forEach(operation -> ERROR_RESPONSES.forEach((status, description) -> {
					if (!operation.getResponses().containsKey(status)) {
						operation.getResponses().addApiResponse(status, problemResponse(description));
					}
				}));
			});
		};
	}

	private static ApiResponse problemResponse(String description) {
		Schema<?> ref = new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA);
		return new ApiResponse().description(description)
			.content(new Content().addMediaType(PROBLEM_MEDIA_TYPE, new MediaType().schema(ref)));
	}

}
