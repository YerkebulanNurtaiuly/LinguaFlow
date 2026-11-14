package com.linguaflow.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scores one live AI eval (QA-03): each case passes or fails with a note, nothing stops
 * at the first failure, and the eval passes when the share of passed cases reaches its
 * threshold, since a model is allowed the odd miss. The table goes to standard output
 * and to {@code target/ai-eval/<name>.md}, which the nightly workflow publishes.
 */
public final class EvalReport {

	public static final Path DIRECTORY = Path.of("target", "ai-eval");

	private record Case(String name, boolean passed, String note) {
	}

	private final String name;

	private final double threshold;

	private final List<Case> cases = new ArrayList<>();

	public EvalReport(String name, double threshold) {
		this.name = name;
		this.threshold = threshold;
	}

	/** Records one case: whether it passed and what the model answered. */
	public void check(String caseName, String actual, boolean passed) {
		cases.add(new Case(caseName, passed, actual));
	}

	public void fail(String caseName, String reason) {
		cases.add(new Case(caseName, false, reason));
	}

	/** Writes the table and fails when fewer than the threshold's share of cases passed. */
	public void finish() {
		long passed = cases.stream().filter(Case::passed).count();
		double rate = cases.isEmpty() ? 0 : (double) passed / cases.size();
		StringBuilder table = new StringBuilder("## ").append(name)
			.append(String.format(": %d of %d passed (%.0f%%, needs %.0f%%)%n%n", passed, cases.size(), rate * 100,
					threshold * 100))
			.append("| Case | Result | Model said |\n|---|---|---|\n");
		for (Case result : cases) {
			table.append("| ")
				.append(result.name())
				.append(" | ")
				.append(result.passed() ? "pass" : "**FAIL**")
				.append(" | ")
				.append(result.note() == null ? "" : result.note().replace("|", "\\|").replace("\n", " "))
				.append(" |\n");
		}
		System.out.println(table);
		try {
			Files.createDirectories(DIRECTORY);
			Files.writeString(DIRECTORY.resolve(name + ".md"), table, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		assertThat(rate).as("%s pass rate (see the table above)", name).isGreaterThanOrEqualTo(threshold);
	}

}
