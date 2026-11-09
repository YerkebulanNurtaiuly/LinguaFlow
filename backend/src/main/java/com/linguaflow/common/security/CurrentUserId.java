package com.linguaflow.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the authenticated user's ID (the access token's {@code sub}) into a controller
 * method parameter of type {@link java.util.UUID}:
 * <pre>{@code
 * @GetMapping("/api/vocabulary")
 * PageResponse<...> list(@CurrentUserId UUID userId, Pageable pageable)
 * }</pre>
 * Use it to scope every query to the caller (AUTH-05). Unauthenticated requests never
 * reach the controller; if one does, the request fails with 401.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUserId {

}
