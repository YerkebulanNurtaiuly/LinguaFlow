package com.linguaflow.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.test.context.support.WithSecurityContext;

/**
 * Like {@code @WithMockUser}, but the authentication is what a real access token
 * produces (QA-01): a {@code JwtAuthenticationToken} named by the user ID, with
 * {@code ROLE_<role>}, so {@code @CurrentUserId} and role checks behave as in production.
 * For {@code @WebMvcTest} slices; full-context tests send a real token from
 * {@link TestUsers}. Per request, use {@link TestJwt#learner} instead.
 */
@Documented
@Inherited
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithJwtUserSecurityContextFactory.class)
public @interface WithJwtUser {

	/** The user ID, the token's {@code sub}. */
	String value() default TestJwt.DEFAULT_USER_ID;

	/** USER or ADMIN. */
	String role() default "USER";

}
