package com.linguaflow.testsupport;

import java.time.Instant;
import java.util.UUID;

import com.linguaflow.common.security.JwtRoleConverter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * Per-request JWT authentication for {@code @WebMvcTest} slices (QA-01), built by the
 * app's own {@link JwtRoleConverter} from claims like a real access token's:
 * <pre>{@code
 * mvc.perform(get("/api/ai/usage").with(TestJwt.learner(userId)));
 * mvc.perform(get("/api/admin/ai-usage").with(TestJwt.admin(adminId)));
 * }</pre>
 * No token is signed or decoded. Full-context tests send real tokens from
 * {@link TestUsers} instead, which also covers the decoder.
 */
public final class TestJwt {

	/** The default user of {@link WithJwtUser}. */
	public static final String DEFAULT_USER_ID = "0190a6f0-0000-7000-8000-0000000000aa";

	private TestJwt() {
	}

	public static RequestPostProcessor learner(UUID userId) {
		return authentication(token(userId, "USER"));
	}

	public static RequestPostProcessor admin(UUID userId) {
		return authentication(token(userId, "ADMIN"));
	}

	/** What the resource server makes of a valid access token for this user and role. */
	public static AbstractAuthenticationToken token(UUID userId, String role) {
		Instant issued = Instant.parse("2026-11-13T09:00:00Z");
		Jwt jwt = Jwt.withTokenValue("test-token")
			.header("alg", "HS256")
			.subject(userId.toString())
			.issuer("linguaflow")
			.claim(JwtRoleConverter.ROLE_CLAIM, role)
			.issuedAt(issued)
			.expiresAt(issued.plusSeconds(900))
			.build();
		return new JwtRoleConverter().convert(jwt);
	}

}
