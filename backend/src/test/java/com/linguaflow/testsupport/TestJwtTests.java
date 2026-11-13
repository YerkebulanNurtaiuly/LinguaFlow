package com.linguaflow.testsupport;

import java.util.UUID;

import com.linguaflow.auth.JwtConfig;
import com.linguaflow.common.config.ClockConfig;
import com.linguaflow.common.config.CorsConfig;
import com.linguaflow.common.config.LinguaFlowProperties;
import com.linguaflow.common.config.SecurityConfig;
import com.linguaflow.common.security.CurrentUserId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The JWT test helpers authenticate like a real access token: user ID and role. */
@WebMvcTest(controllers = TestJwtTests.WhoAmIController.class)
@Import({ TestJwtTests.WhoAmIController.class, TestJwtTests.PropertiesConfig.class, SecurityConfig.class,
		CorsConfig.class, JwtConfig.class, ClockConfig.class })
@ActiveProfiles("test")
class TestJwtTests {

	private static final UUID ANNA = UUID.fromString("0190a6f0-0000-7000-8000-000000000123");

	@Autowired
	private MockMvc mvc;

	@Test
	void aLearnerIsTheCurrentUserButNoAdmin() throws Exception {
		mvc.perform(get("/api/test/me").with(TestJwt.learner(ANNA)))
			.andExpect(status().isOk())
			.andExpect(content().string(ANNA.toString()));
		mvc.perform(get("/api/admin/test").with(TestJwt.learner(ANNA))).andExpect(status().isForbidden());
	}

	@Test
	void anAdminReachesAdminRoutes() throws Exception {
		mvc.perform(get("/api/admin/test").with(TestJwt.admin(ANNA))).andExpect(status().isOk());
	}

	@Test
	void withoutAuthenticationTheRequestIsRejected() throws Exception {
		mvc.perform(get("/api/test/me")).andExpect(status().isUnauthorized());
	}

	@Test
	@WithJwtUser
	void theAnnotationUsesTheDefaultLearner() throws Exception {
		mvc.perform(get("/api/test/me")).andExpect(content().string(TestJwt.DEFAULT_USER_ID));
		mvc.perform(get("/api/admin/test")).andExpect(status().isForbidden());
	}

	@Test
	@WithJwtUser(value = "0190a6f0-0000-7000-8000-000000000123", role = "ADMIN")
	void theAnnotationTakesAUserAndARole() throws Exception {
		mvc.perform(get("/api/test/me")).andExpect(content().string(ANNA.toString()));
		mvc.perform(get("/api/admin/test")).andExpect(status().isOk());
	}

	@RestController
	static class WhoAmIController {

		@GetMapping("/api/test/me")
		String me(@CurrentUserId UUID userId) {
			return userId.toString();
		}

		@GetMapping("/api/admin/test")
		String admin() {
			return "ok";
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableConfigurationProperties(LinguaFlowProperties.class)
	static class PropertiesConfig {

	}

}
