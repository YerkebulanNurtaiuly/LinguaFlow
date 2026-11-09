package com.linguaflow.common.config;

import com.linguaflow.TestcontainersConfiguration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OpenApiDocsTests {

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
