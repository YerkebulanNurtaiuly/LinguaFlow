package com.linguaflow.auth;

import java.time.Clock;
import java.time.Instant;

import com.linguaflow.common.config.LinguaFlowProperties;
import com.linguaflow.user.User;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues short-lived access tokens: {@code sub} = user ID, {@code role} = USER/ADMIN. */
@Service
public class AccessTokenService {

	public static final String ROLE_CLAIM = "role";

	private final JwtEncoder encoder;

	private final LinguaFlowProperties.Jwt settings;

	private final Clock clock;

	AccessTokenService(JwtEncoder encoder, LinguaFlowProperties properties, Clock clock) {
		this.encoder = encoder;
		this.settings = properties.security().jwt();
		this.clock = clock;
	}

	public IssuedAccessToken issue(User user) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(settings.accessTokenTtl());
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(settings.issuer())
			.subject(user.getId().toString())
			.issuedAt(now)
			.expiresAt(expiresAt)
			.claim(ROLE_CLAIM, user.getRole().name())
			.build();
		String token = encoder
			.encode(JwtEncoderParameters.from(JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
			.getTokenValue();
		return new IssuedAccessToken(token, expiresAt);
	}

	public record IssuedAccessToken(String value, Instant expiresAt) {
	}

}
