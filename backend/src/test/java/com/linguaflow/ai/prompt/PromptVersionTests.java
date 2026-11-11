package com.linguaflow.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/**
 * A released prompt version never changes (AI-03): {@code ai_requests.prompt_version}
 * and the AI cache key must always name the exact text used. This test keeps a hash of
 * each version's prepared text (partials included) in {@code prompt-versions.txt}.
 * <ul>
 * <li>Changed a template or partial? Add {@code v<n+1>} files, bump the version in
 * {@link PromptTemplate}, then record it with
 * {@code ./mvnw test -Dtest=PromptVersionTests -Dprompts.update=true}.</li>
 * <li>Only while a version has never been deployed may you edit it in place and
 * re-record it.</li>
 * </ul>
 * Old entries stay in the file as the history of released versions.
 */
class PromptVersionTests {

	static final Path SNAPSHOT = Path.of("src", "test", "resources", "prompt-versions.txt");

	@Test
	void everyTemplateVersionMatchesItsRecordedText() throws IOException {
		PromptLibrary library = new PromptLibrary();
		Map<String, String> recorded = read();
		Map<String, String> current = new TreeMap<>();
		for (PromptTemplate template : PromptTemplate.values()) {
			PromptLibrary.Source source = library.source(template);
			current.put(template.versionTag(), hash(source.system() + "\n\u0000\n" + source.user()));
		}

		if (Boolean.getBoolean("prompts.update")) {
			Map<String, String> updated = new TreeMap<>(recorded);
			updated.putAll(current);
			StringBuilder text = new StringBuilder(
					"# Hashes of released prompt versions; see PromptVersionTests. Never change a line, add one.\n");
			updated.forEach((tag, hash) -> text.append(tag).append(' ').append(hash).append('\n'));
			Files.createDirectories(SNAPSHOT.getParent());
			Files.writeString(SNAPSHOT, text.toString());
			return;
		}
		current.forEach((tag, hash) -> {
			assertThat(recorded).as("""
					%s isn't recorded yet. Record it with \
					./mvnw test -Dtest=PromptVersionTests -Dprompts.update=true""".formatted(tag)).containsKey(tag);
			assertThat(hash).as("""
					The text of %s changed. Write the change as a new version (new v<n+1> files and a version \
					bump in PromptTemplate), then record it with -Dprompts.update=true. Only a version that \
					was never deployed may be edited in place.""".formatted(tag)).isEqualTo(recorded.get(tag));
		});
	}

	private static Map<String, String> read() throws IOException {
		Map<String, String> entries = new TreeMap<>();
		if (!Files.exists(SNAPSHOT)) {
			return entries;
		}
		for (String line : Files.readAllLines(SNAPSHOT)) {
			if (!line.isBlank() && !line.startsWith("#")) {
				String[] parts = line.strip().split("\\s+");
				entries.put(parts[0], parts[1]);
			}
		}
		return entries;
	}

	private static String hash(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 12);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
