package com.linguaflow.common.error;

import java.util.List;

import com.linguaflow.common.config.CorsConfig;
import com.linguaflow.common.config.LinguaFlowProperties;
import com.linguaflow.common.config.SecurityConfig;
import com.linguaflow.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every error, whatever its source, must come back as application/problem+json with
 * the same fields.
 */
@WebMvcTest(properties = "linguaflow.cors.allowed-origins=http://localhost:5173")
@Import({ ErrorHandlingTests.TestController.class, ErrorHandlingTests.PropertiesConfig.class, SecurityConfig.class,
		CorsConfig.class })
@ActiveProfiles("test")
class ErrorHandlingTests {

	private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

	@Autowired
	private MockMvc mvc;

	@Nested
	@WithMockUser
	class AuthenticatedRequests {

		@Test
		void validRequestSucceeds() throws Exception {
			mvc.perform(post("/api/test/items").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Hund\",\"level\":2}")).andExpect(status().isOk());
		}

		@Test
		void invalidBodyReturnsFieldErrors() throws Exception {
			expectProblem(mvc.perform(post("/api/test/items").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\" \",\"level\":42}")), 400, "VALIDATION_FAILED", "/api/test/items")
				.andExpect(jsonPath("$.errors.length()").value(2))
				.andExpect(jsonPath("$.errors[0].field").value("level"))
				.andExpect(jsonPath("$.errors[0].message").value("must be less than or equal to 10"))
				.andExpect(jsonPath("$.errors[1].field").value("name"));
		}

		@Test
		void invalidQueryParameterReturnsFieldErrors() throws Exception {
			expectProblem(mvc.perform(get("/api/test/items").param("level", "0")), 400, "VALIDATION_FAILED",
					"/api/test/items")
				.andExpect(jsonPath("$.errors[0].field").value("level"));
		}

		@Test
		void malformedJsonIsRejectedWithoutLeakingParserDetails() throws Exception {
			expectProblem(mvc.perform(post("/api/test/items").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":")), 400, "MALFORMED_REQUEST", "/api/test/items")
				.andExpect(jsonPath("$.detail").value("Request body is missing or not valid JSON"));
		}

		@Test
		void wrongParameterTypeIsBadRequest() throws Exception {
			expectProblem(mvc.perform(get("/api/test/items/abc")), 400, "BAD_REQUEST", "/api/test/items/abc");
		}

		@Test
		void notFoundExceptionMapsTo404() throws Exception {
			expectProblem(mvc.perform(get("/api/test/items/7")), 404, "NOT_FOUND", "/api/test/items/7")
				.andExpect(jsonPath("$.detail").value("Item 7 not found"));
		}

		@Test
		void conflictExceptionMapsTo409() throws Exception {
			expectProblem(mvc.perform(post("/api/test/conflict").with(csrf())), 409, "CONFLICT", "/api/test/conflict")
				.andExpect(jsonPath("$.detail").value("Word already exists"));
		}

		@Test
		void unknownPathIs404() throws Exception {
			expectProblem(mvc.perform(get("/api/does-not-exist")), 404, "NOT_FOUND", "/api/does-not-exist");
		}

		@Test
		void wrongMethodIs405() throws Exception {
			expectProblem(mvc.perform(post("/api/test/boom").with(csrf())), 405, "METHOD_NOT_ALLOWED", "/api/test/boom");
		}

		@Test
		void unsupportedContentTypeIs415() throws Exception {
			expectProblem(mvc.perform(post("/api/test/items").with(csrf()).contentType(MediaType.TEXT_PLAIN).content("x")), 415,
					"UNSUPPORTED_MEDIA_TYPE", "/api/test/items");
		}

		@Test
		void unexpectedExceptionIs500WithoutInternals() throws Exception {
			expectProblem(mvc.perform(get("/api/test/boom")), 500, "INTERNAL_ERROR", "/api/test/boom")
				.andExpect(jsonPath("$.detail", not(matchesPattern(".*secret.*"))));
		}

		@Test
		void pageSizeIsCappedAndSortIsReported() throws Exception {
			mvc.perform(get("/api/test/page").param("size", "500").param("sort", "name,desc"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.size").value(100))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.sort[0].property").value("name"))
				.andExpect(jsonPath("$.sort[0].direction").value("DESC"));
		}

	}

	@Test
	@WithMockUser
	void postWithoutCsrfTokenIs403Problem() throws Exception {
		// CSRF stays on until AUTH-04 decides the strategy for the refresh-token cookie
		expectProblem(mvc.perform(post("/api/test/conflict")), 403, "FORBIDDEN", "/api/test/conflict");
	}

	@Test
	void unauthenticatedRequestIs401Problem() throws Exception {
		expectProblem(mvc.perform(get("/api/test/items/7")), 401, "UNAUTHORIZED", "/api/test/items/7");
	}

	@Test
	void validCorrelationIdIsEchoedInHeaderAndBody() throws Exception {
		mvc.perform(get("/api/test/items/7").header("X-Correlation-Id", "abc-123"))
			.andExpect(header().string("X-Correlation-Id", "abc-123"))
			.andExpect(jsonPath("$.correlationId").value("abc-123"));
	}

	@Test
	void unsafeCorrelationIdIsReplaced() throws Exception {
		mvc.perform(get("/api/test/items/7").header("X-Correlation-Id", "bad id\nwith newline"))
			.andExpect(header().string("X-Correlation-Id", matchesPattern(UUID_PATTERN)));
	}

	@Test
	void corsPreflightAllowsConfiguredOrigin() throws Exception {
		mvc.perform(options("/api/test/items").header(HttpHeaders.ORIGIN, "http://localhost:5173")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
	}

	@Test
	void corsPreflightRejectsOtherOrigins() throws Exception {
		mvc.perform(options("/api/test/items").header(HttpHeaders.ORIGIN, "https://evil.example")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
			.andExpect(status().isForbidden());
	}

	private ResultActions expectProblem(ResultActions result, int status, String code, String instance)
			throws Exception {
		return result.andExpect(status().is(status))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(status))
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.title").value(ErrorCode.valueOf(code).title()))
			.andExpect(jsonPath("$.detail", notNullValue()))
			.andExpect(jsonPath("$.instance").value(instance))
			.andExpect(jsonPath("$.correlationId", matchesPattern(UUID_PATTERN)))
			.andExpect(jsonPath("$.timestamp", notNullValue()));
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableConfigurationProperties(LinguaFlowProperties.class)
	static class PropertiesConfig {

	}

	record ItemRequest(@NotBlank String name, @Min(1) @Max(10) int level) {
	}

	@RestController
	@RequestMapping("/api/test")
	static class TestController {

		@PostMapping("/items")
		ItemRequest create(@Valid @RequestBody ItemRequest request) {
			return request;
		}

		@GetMapping("/items")
		List<String> list(@RequestParam @Min(1) int level) {
			return List.of();
		}

		@GetMapping("/items/{id}")
		ItemRequest get(@PathVariable long id) {
			throw NotFoundException.of("Item", id);
		}

		@PostMapping("/conflict")
		void conflict() {
			throw new ConflictException("Word already exists");
		}

		@GetMapping("/boom")
		void boom() {
			throw new IllegalStateException("db password is secret");
		}

		@GetMapping("/page")
		PageResponse<String> page(Pageable pageable) {
			return PageResponse.from(new PageImpl<>(List.of("a"), pageable, 1));
		}

	}

}
