package com.linguaflow.common.ratelimit;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import com.linguaflow.common.config.LinguaFlowProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Rate-limits POST /api/auth/login and /api/auth/register per client IP (AUTH-06). Runs
 * right after the correlation-ID filter and before Spring Security, so floods are cut off
 * before any password hashing. Rejections go through the MVC exception resolver and get
 * the standard ProblemDetail body (429, code TOO_MANY_REQUESTS, Retry-After header).
 * <p>
 * The client IP is {@code request.getRemoteAddr()}, which Tomcat resolves from
 * X-Forwarded-For only for trusted internal proxies ({@code server.forward-headers-strategy}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthRateLimitFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);

	private final boolean enabled;

	private final Map<String, RateLimiter> limitersByPath;

	private final HandlerExceptionResolver exceptionResolver;

	AuthRateLimitFilter(LinguaFlowProperties properties,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
		LinguaFlowProperties.RateLimit settings = properties.rateLimit();
		this.enabled = settings.enabled();
		this.limitersByPath = Map.of("/api/auth/login", new RateLimiter("login", settings.login()),
				"/api/auth/register", new RateLimiter("register", settings.register()));
		this.exceptionResolver = exceptionResolver;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !enabled || !"POST".equals(request.getMethod())
				|| !limitersByPath.containsKey(request.getRequestURI());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		RateLimiter limiter = limitersByPath.get(request.getRequestURI());
		String clientIp = request.getRemoteAddr();
		Optional<Duration> wait = limiter.tryConsume(clientIp);
		if (wait.isPresent()) {
			log.warn("Rate limit '{}' exceeded for {}", limiter.name(), clientIp);
			exceptionResolver.resolveException(request, response, null, new RateLimitExceededException(wait.get()));
			return;
		}
		chain.doFilter(request, response);
	}

}
