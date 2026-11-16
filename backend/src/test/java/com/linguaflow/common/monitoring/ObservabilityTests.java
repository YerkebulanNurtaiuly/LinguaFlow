package com.linguaflow.common.monitoring;

import com.linguaflow.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OPS-04: the metrics endpoint is closed except to a scraper with the token, it carries the
 * AI and HTTP metrics, and browser error reports are logged without personal data.
 */
@SpringBootTest(properties = "linguaflow.metrics.token=test-metrics-token")
@AutoConfigureMockMvc
@AutoConfigureMetrics
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void metricsNeedTheScrapersToken() throws Exception {
		mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
		mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer wrong"))
			.andExpect(status().isUnauthorized());
		// A user's JWT doesn't open it either
		mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.e30.x"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void theScraperSeesHttpAndAiMetrics() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());

		String body = mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer test-metrics-token"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("text/plain"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(body).contains("linguaflow_ai_jobs{application=\"linguaflow\",status=\"queued\"}")
			.contains("linguaflow_ai_jobs{application=\"linguaflow\",status=\"dead\"}")
			.contains("http_server_requests_seconds_bucket")
			.contains("jvm_memory_used_bytes");
	}

	@Test
	void browserErrorsAreLoggedWithoutPersonalData(CapturedOutput output) throws Exception {
		mvc.perform(post("/api/client-errors").contentType(MediaType.APPLICATION_JSON).content("""
				{"kind": "render", "message": "Cannot read properties of undefined (reading 'map') for anna@example.de",
				 "stack": "TypeError at VocabularyTable (index-abc.js:1:2)", "path": "/vocabulary?q=Hund#top",
				 "release": "dfba83c"}""")).andExpect(status().isNoContent());

		assertThat(output.getAll()).contains("Browser render on /vocabulary (release dfba83c)")
			.contains("for [email]")
			.contains("TypeError at VocabularyTable")
			.doesNotContain("anna@example.de")
			.doesNotContain("q=Hund");
	}

	@Test
	void badReportsAreRejected() throws Exception {
		mvc.perform(post("/api/client-errors").contentType(MediaType.APPLICATION_JSON)
			.content("{\"kind\": \"keylogger\", \"message\": \"x\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("kind"));
		mvc.perform(post("/api/client-errors").contentType(MediaType.APPLICATION_JSON)
			.content("{\"kind\": \"error\", \"message\": \"" + "x".repeat(501) + "\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void personalDataIsBlanked() {
		assertThat(ClientErrorController.clean("call +49 151 23456789 or mail a.b+c@mail.example.com"))
			.isEqualTo("call +[number] or mail [email]");
		assertThat(ClientErrorController.clean(null)).isEqualTo("-");
	}

}
