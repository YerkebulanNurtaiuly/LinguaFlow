package com.linguaflow.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Delegating encoder: new hashes are BCrypt and stored with an {@code {bcrypt}} prefix, so
 * the algorithm can be upgraded later (e.g. to Argon2) while old hashes keep working.
 * The dev seed's demo password uses the same format.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordEncoderConfig {

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

}
