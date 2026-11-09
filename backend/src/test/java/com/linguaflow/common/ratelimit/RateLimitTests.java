package com.linguaflow.common.ratelimit;

import com.linguaflow.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AUTH-06 with small limits. Buckets live for the whole (cached) context, so every test
 * uses its own client IP.
 */
@SpringBootTest(properties = { "linguaflow.rate-limit.enabled=true", "linguaflow.rate-limit.login.requests=3",
		"linguaflow.rate-limit.login.period=1m", "linguaflow.rate-limit.register.requests=2",
		"linguaflow.rate-limit.register.period=1h", "linguaflow.rate-limit.password-reset.requests=2",
		"linguaflow.rate-limit.password-reset.period=1h" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class RateLimitTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void loginIsLimitedPerIpAndCountsFailedAttempts() throws Exception {
		for (int i = 0; i < 3; i++) {
			login("203.0.113.1").andExpect(status().isUnauthorized());
		}
		login("203.0.113.1").andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
			.andExpect(jsonPath("$.status").value(429))
			.andExpect(jsonPath("$.correlationId").isNotEmpty())
			.andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("[1-9][0-9]*")));
	}

	@Test
	void otherIpsHaveTheirOwnBudget() throws Exception {
		for (int i = 0; i < 4; i++) {
			login("203.0.113.2");
		}
		login("203.0.113.3").andExpect(status().isUnauthorized());
	}

	@Test
	void registrationHasItsOwnStricterLimit() throws Exception {
		register("203.0.113.4", "a@example.de").andExpect(status().isCreated());
		register("203.0.113.4", "b@example.de").andExpect(status().isCreated());
		register("203.0.113.4", "c@example.de").andExpect(status().isTooManyRequests());

		// The login budget of the same IP is untouched
		login("203.0.113.4").andExpect(status().isUnauthorized());
	}

	@Test
	void passwordResetRequestsAreLimited() throws Exception {
		for (int i = 0; i < 2; i++) {
			resetRequest("203.0.113.6").andExpect(status().isAccepted());
		}
		resetRequest("203.0.113.6").andExpect(status().isTooManyRequests());
	}

	private ResultActions resetRequest(String ip) throws Exception {
		return mvc.perform(post("/api/auth/password-reset/request").with(fromIp(ip))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"nobody@example.de\"}"));
	}

	@Test
	void otherEndpointsAreNotLimited() throws Exception {
		for (int i = 0; i < 6; i++) {
			mvc.perform(post("/api/auth/logout").with(fromIp("203.0.113.5"))).andExpect(status().isNoContent());
		}
	}

	private ResultActions login(String ip) throws Exception {
		return mvc.perform(post("/api/auth/login").with(fromIp(ip))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"nobody@example.de\",\"password\":\"wrong-password\"}"));
	}

	private ResultActions register(String ip, String email) throws Exception {
		return mvc.perform(post("/api/auth/register").with(fromIp(ip))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"%s\",\"password\":\"correct-horse-battery\",\"displayName\":\"R\"}".formatted(email)));
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor fromIp(String ip) {
		return request -> {
			request.setRemoteAddr(ip);
			return request;
		};
	}

}
