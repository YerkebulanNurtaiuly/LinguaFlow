package com.linguaflow.common.security;

import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Turns a verified access token into an authentication: name = {@code sub} (user ID),
 * authority = {@code ROLE_<role claim>}. Tokens without a role get no authorities.
 */
public class JwtRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	public static final String ROLE_CLAIM = "role";

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		String role = jwt.getClaimAsString(ROLE_CLAIM);
		List<SimpleGrantedAuthority> authorities = (role == null || role.isBlank()) ? List.of()
				: List.of(new SimpleGrantedAuthority("ROLE_" + role));
		return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
	}

}
