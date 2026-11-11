package com.linguaflow.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Model output is data and is never executed (AI-07). The backend has no way to run
 * text: no script engines, expression parsers, shell commands or dynamic class loading.
 * If one is ever genuinely needed, it must not see AI output; add it here with a reason.
 */
class NoCodeExecutionTests {

	private static final List<Pattern> FORBIDDEN = List.of(Pattern.compile("javax\\.script"),
			Pattern.compile("\\bScriptEngine"), Pattern.compile("Runtime\\.getRuntime\\(\\)\\s*\\.\\s*exec"),
			Pattern.compile("\\bnew ProcessBuilder\\b"), Pattern.compile("\\bSpelExpressionParser\\b"),
			Pattern.compile("\\bExpressionParser\\b"), Pattern.compile("\\bGroovyShell\\b"),
			Pattern.compile("\\bClass\\.forName\\("), Pattern.compile("\\bdefineClass\\("),
			Pattern.compile("\\bObjectInputStream\\b"));

	@Test
	void theBackendNeverRunsText() throws IOException {
		try (Stream<Path> sources = Files.walk(Path.of("src", "main", "java"))) {
			List<String> offenders = sources.filter(path -> path.toString().endsWith(".java")).flatMap(path -> {
				String code = read(path);
				return FORBIDDEN.stream()
					.filter(pattern -> pattern.matcher(code).find())
					.map(pattern -> path + " uses " + pattern.pattern());
			}).toList();
			assertThat(offenders).as("code that could execute text").isEmpty();
		}
	}

	private static String read(Path path) {
		try {
			return Files.readString(path);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
