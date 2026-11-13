package com.linguaflow.testsupport;

import java.util.UUID;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

/** Builds the security context for {@link WithJwtUser}. */
class WithJwtUserSecurityContextFactory implements WithSecurityContextFactory<WithJwtUser> {

	@Override
	public SecurityContext createSecurityContext(WithJwtUser annotation) {
		return new SecurityContextImpl(TestJwt.token(UUID.fromString(annotation.value()), annotation.role()));
	}

}
