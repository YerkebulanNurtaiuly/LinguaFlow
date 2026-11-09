package com.linguaflow.common.logging;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation ID (taken from the {@value #HEADER} request header
 * when it is well-formed, otherwise generated), exposes it in the MDC for log lines and
 * error bodies, echoes it in the response header, and logs one line per request.
 * Runs before Spring Security so rejected requests are covered too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Correlation-Id";

	public static final String MDC_KEY = "correlationId";

	// Client-supplied IDs end up in logs, so only accept a safe, bounded charset
	private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String correlationId = resolveCorrelationId(request.getHeader(HEADER));
		MDC.put(MDC_KEY, correlationId);
		response.setHeader(HEADER, correlationId);
		long start = System.nanoTime();
		try {
			chain.doFilter(request, response);
		}
		finally {
			logRequest(request, response, (System.nanoTime() - start) / 1_000_000);
			MDC.remove(MDC_KEY);
		}
	}

	static String resolveCorrelationId(String header) {
		if (header != null && VALID_ID.matcher(header).matches()) {
			return header;
		}
		return UUID.randomUUID().toString();
	}

	private static void logRequest(HttpServletRequest request, HttpServletResponse response, long durationMs) {
		String path = request.getRequestURI();
		if (path.startsWith("/actuator/health")) {
			// Health probes would flood the log; keep them at debug
			log.debug("{} {} -> {} ({} ms)", request.getMethod(), path, response.getStatus(), durationMs);
			return;
		}
		log.info("{} {} -> {} ({} ms)", request.getMethod(), path, response.getStatus(), durationMs);
	}

}
