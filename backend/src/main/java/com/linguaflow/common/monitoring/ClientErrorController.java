package com.linguaflow.common.monitoring;

import java.util.regex.Pattern;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern.Flag;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browser errors reported by the frontend (OPS-04). They're logged at ERROR on the logger
 * {@code linguaflow.client}, which sends them to Sentry like backend errors, so the
 * browser never talks to a third party and the CSP stays {@code 'self'}.
 * <p>
 * Open to visitors who aren't logged in (crashes happen on the login page too) and
 * rate-limited per IP (AuthRateLimitFilter). Reports carry no personal data: the frontend
 * sends the path without its query, and email addresses and long numbers are blanked here
 * as well, in case an error message quotes user input.
 */
@RestController
@Tag(name = "Monitoring", description = "Error reports from the browser")
class ClientErrorController {

	private static final Logger CLIENT = LoggerFactory.getLogger("linguaflow.client");

	private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");

	private static final Pattern LONG_NUMBER = Pattern.compile("\\d[\\d ]{7,}\\d");

	@Schema(name = "ClientErrorReport", description = "One error that happened in the browser")
	record ClientErrorReport(
			@Schema(description = "Where it came from", example = "render") @NotBlank @jakarta.validation.constraints.Pattern(
					regexp = "render|error|unhandledrejection", flags = Flag.CASE_INSENSITIVE) String kind,
			@Schema(example = "Cannot read properties of undefined (reading 'map')") @NotBlank @Size(max = 500) String message,
			@Schema(description = "The stack trace, if the browser gave one") @Nullable @Size(max = 4000) String stack,
			@Schema(description = "The page's path, without query or fragment", example = "/vocabulary") @Nullable @Size(max = 300) String path,
			@Schema(description = "The deployed frontend version (version.txt)") @Nullable @Size(max = 64) String release) {
	}

	@PostMapping("/api/client-errors")
	@SecurityRequirements
	@Operation(summary = "Report a browser error",
			description = "Logs the error for the developers (and Sentry, where configured). Accepts reports "
					+ "without a login; limited per IP.")
	@ApiResponse(responseCode = "204", description = "Reported")
	ResponseEntity<Void> report(@Valid @RequestBody ClientErrorReport report) {
		CLIENT.error("Browser {} on {} (release {}): {}\n{}", report.kind().toLowerCase(java.util.Locale.ROOT),
				clean(withoutQuery(report.path())), clean(report.release()), clean(report.message()),
				clean(report.stack()));
		return ResponseEntity.noContent().build();
	}

	private static @Nullable String withoutQuery(@Nullable String path) {
		if (path == null) {
			return null;
		}
		int cut = path.length();
		for (char marker : new char[] { '?', '#' }) {
			int at = path.indexOf(marker);
			if (at >= 0 && at < cut) {
				cut = at;
			}
		}
		return path.substring(0, cut);
	}

	/** Blanks email addresses and long numbers, and keeps the log on one record. */
	static String clean(@Nullable String text) {
		if (text == null || text.isBlank()) {
			return "-";
		}
		String blanked = LONG_NUMBER.matcher(EMAIL.matcher(text).replaceAll("[email]")).replaceAll("[number]");
		return blanked.replaceAll("[\\p{Cntrl}&&[^\n\t]]", "");
	}

}
