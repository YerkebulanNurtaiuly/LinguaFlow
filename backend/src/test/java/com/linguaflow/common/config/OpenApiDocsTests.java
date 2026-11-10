package com.linguaflow.common.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.linguaflow.TestcontainersConfiguration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OpenApiDocsTests {

	/** Spec snapshot the frontend generates its API types from (npm run api:generate). */
	static final Path SNAPSHOT = Path.of("..", "frontend", "openapi.json");

	/** Same switches as the dev profile, on top of the test profile's datasource. */
	@Nested
	@SpringBootTest(properties = { "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true",
			"springdoc.show-actuator=true" })
	@AutoConfigureMockMvc
	@Import(TestcontainersConfiguration.class)
	@ActiveProfiles("test")
	class WhenEnabled {

		@Autowired
		private MockMvc mvc;

		@Test
		void fullSpecDeclaresJwtBearerSchemeAsDefaultSecurity() throws Exception {
			mvc.perform(get("/v3/api-docs/all"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.info.title").value("LinguaFlow API"))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
				.andExpect(jsonPath("$.security[0].bearerAuth").isArray())
				.andExpect(jsonPath("$.paths['/actuator/health']").exists());
		}

		@Test
		void errorBodySchemaIsPublished() throws Exception {
			mvc.perform(get("/v3/api-docs/all"))
				.andExpect(jsonPath("$.components.schemas.ApiProblem.properties.code.enum").value(hasItems(
						"VALIDATION_FAILED", "NOT_FOUND", "UNAUTHORIZED", "INTERNAL_ERROR")))
				.andExpect(jsonPath("$.components.schemas.FieldError.properties.field").exists())
				.andExpect(jsonPath("$.servers[0].url").value("/"));
		}

		/**
		 * Fails when the API changed but frontend/openapi.json was not regenerated. To update:
		 * {@code ./mvnw test -Dtest=OpenApiDocsTests -Dopenapi.update=true}, then
		 * {@code npm run api:generate} in frontend/.
		 */
		@Test
		void committedSpecSnapshotIsUpToDate() throws Exception {
			String actual = mvc.perform(get("/v3/api-docs/all"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
			JsonNode actualTree = json.readTree(actual);

			if (Boolean.getBoolean("openapi.update")) {
				writeSnapshot(actualTree);
				return;
			}
			assertThat(SNAPSHOT).as("OpenAPI snapshot; create it with -Dopenapi.update=true").exists();
			assertThat(json.readTree(Files.readString(SNAPSHOT)))
				.as("frontend/openapi.json is out of date. Run: ./mvnw test -Dtest=OpenApiDocsTests "
						+ "-Dopenapi.update=true && (cd ../frontend && npm run api:generate)")
				.isEqualTo(actualTree);
		}

		/**
		 * An operation that declares only error @ApiResponses loses springdoc's default 200,
		 * and the generated frontend types then have no response body for it.
		 */
		@Test
		void everyOperationDocumentsASuccessResponse() throws Exception {
			JsonNode spec = json.readTree(mvc.perform(get("/v3/api-docs/all"))
				.andReturn()
				.getResponse()
				.getContentAsString(StandardCharsets.UTF_8));
			java.util.List<String> missing = new java.util.ArrayList<>();
			for (var path : spec.get("paths").properties()) {
				for (var operation : path.getValue().properties()) {
					boolean success = operation.getValue()
						.path("responses")
						.propertyNames()
						.stream()
						.anyMatch(code -> code.startsWith("2"));
					if (!success) {
						missing.add(operation.getKey().toUpperCase() + " " + path.getKey());
					}
				}
			}
			assertThat(missing).as("operations without a 2xx response; add @ApiResponse(responseCode = \"200\")")
				.isEmpty();
		}

		private void writeSnapshot(JsonNode tree) throws IOException {
			Files.writeString(SNAPSHOT, json.writerWithDefaultPrettyPrinter().writeValueAsString(tree) + "\n");
		}

		@Autowired
		private JsonMapper json;

		@Test
		void swaggerUiListsOneGroupPerModule() throws Exception {
			mvc.perform(get("/v3/api-docs/swagger-config"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.urls[*].name").value(hasItems("all", "auth", "user", "vocabulary", "grammar",
						"learning", "flashcard", "exercise", "progress", "ai")));
		}

		@Test
		void swaggerUiIsPublic() throws Exception {
			mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
		}

	}

	/** Default (and prod): docs are off and their paths are not public. */
	@Nested
	@SpringBootTest
	@AutoConfigureMockMvc
	@Import(TestcontainersConfiguration.class)
	@ActiveProfiles("test")
	class WhenDisabled {

		@Autowired
		private MockMvc mvc;

		@Test
		void apiDocsAreNotExposed() throws Exception {
			mvc.perform(get("/v3/api-docs/all")).andExpect(status().isUnauthorized());
			mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
		}

	}

}
