package com.linguaflow;

import org.springframework.boot.SpringApplication;

/**
 * Runs the app locally against a Testcontainers PostgreSQL (no local DB needed):
 * {@code ./mvnw spring-boot:test-run}
 */
public class TestLinguaFlowApplication {

	public static void main(String[] args) {
		SpringApplication.from(LinguaFlowApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
