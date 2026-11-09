package com.linguaflow.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.linguaflow.common.config.LinguaFlowProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * HS256 access tokens signed with {@code linguaflow.security.jwt.secret}. The decoder
 * checks signature, issuer and expiry (using the application {@link Clock}); AUTH-04
 * plugs it into the security filter chain.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

	static final MacAlgorithm ALGORITHM = MacAlgorithm.HS256;

	@Bean
	JwtEncoder jwtEncoder(LinguaFlowProperties properties) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
	}

	@Bean
	JwtDecoder jwtDecoder(LinguaFlowProperties properties, Clock clock) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties))
			.macAlgorithm(ALGORITHM)
			.build();
		JwtTimestampValidator timestamps = new JwtTimestampValidator();
		timestamps.setClock(clock);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps,
				new JwtIssuerValidator(properties.security().jwt().issuer())));
		return decoder;
	}

	private static SecretKey signingKey(LinguaFlowProperties properties) {
		byte[] secret = properties.security().jwt().secret().getBytes(StandardCharsets.UTF_8);
		return new SecretKeySpec(secret, "HmacSHA256");
	}

}
