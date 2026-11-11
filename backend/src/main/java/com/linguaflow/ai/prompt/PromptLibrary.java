package com.linguaflow.ai.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.linguaflow.ai.LlmPrompt;
import org.springframework.stereotype.Component;

/**
 * Loads every {@link PromptTemplate} from {@code classpath:prompts/} and fills them in
 * (AI-03). Problems in the files fail at startup, not at the first call:
 * <ul>
 * <li>a missing file or partial, or malformed tags;</li>
 * <li>a variable the template doesn't declare, or a required one it never uses;</li>
 * <li>a system prompt using anything but the language names, which would change it per
 * call and defeat prompt caching.</li>
 * </ul>
 * Rendering refuses missing required values and values the template doesn't declare,
 * which catches variables meant for another template.
 */
@Component
public class PromptLibrary {

	static final String ROOT = "prompts/";

	private static final Map<String, PromptVar> BY_KEY = Arrays.stream(PromptVar.values())
		.collect(Collectors.toMap(PromptVar::key, v -> v));

	/** A template's prepared text: partials resolved, line endings normalized. */
	record Source(String system, String user) {
	}

	private final Map<PromptTemplate, Source> sources = new EnumMap<>(PromptTemplate.class);

	public PromptLibrary() {
		this(PromptLibrary::readClasspath);
	}

	/** @param files reads a path below {@code prompts/}, e.g. {@code partials/x.md} */
	PromptLibrary(Function<String, String> files) {
		Function<String, String> partials = name -> files.apply("partials/" + name + ".md");
		for (PromptTemplate template : PromptTemplate.values()) {
			String base = template.id() + "/v" + template.version();
			try {
				Source source = new Source(PromptText.prepare(files.apply(base + ".system.md"), partials),
						PromptText.prepare(files.apply(base + ".user.md"), partials));
				check(template, source);
				sources.put(template, source);
			}
			catch (RuntimeException ex) {
				throw new IllegalStateException("Prompt template " + base + ": " + ex.getMessage(), ex);
			}
		}
	}

	public RenderedPrompt render(PromptTemplate template, PromptVariables variables) {
		Map<PromptVar, String> values = variables.values();
		Set<PromptVar> missing = new HashSet<>(template.required());
		missing.removeAll(values.keySet());
		if (!missing.isEmpty()) {
			throw new IllegalArgumentException(template.versionTag() + " needs " + keys(missing));
		}
		Set<PromptVar> unknown = values.keySet()
			.stream()
			.filter(variable -> !template.allows(variable))
			.collect(Collectors.toSet());
		if (!unknown.isEmpty()) {
			throw new IllegalArgumentException(template.versionTag() + " doesn't take " + keys(unknown));
		}
		Map<String, String> byKey = values.entrySet()
			.stream()
			.collect(Collectors.toMap(entry -> entry.getKey().key(), Map.Entry::getValue));
		Source source = sources.get(template);
		return new RenderedPrompt(template,
				new LlmPrompt(PromptText.render(source.system(), byKey), PromptText.render(source.user(), byKey)),
				variables.flags());
	}

	/** The prepared text, for {@code PromptVersionTests}. */
	Source source(PromptTemplate template) {
		return sources.get(template);
	}

	private static void check(PromptTemplate template, Source source) {
		Set<PromptVar> systemVars = variables(source.system());
		Set<PromptVar> userVars = variables(source.user());
		systemVars.forEach(variable -> {
			if (!PromptTemplate.stableVariables().contains(variable)) {
				throw new IllegalStateException("the system prompt may only use " + keys(PromptTemplate.stableVariables())
						+ ", not {{" + variable.key() + "}}; per-call data belongs in the user prompt");
			}
		});
		Set<PromptVar> used = new HashSet<>(systemVars);
		used.addAll(userVars);
		used.forEach(variable -> {
			if (!template.allows(variable)) {
				throw new IllegalStateException("uses {{" + variable.key() + "}}, which the template doesn't declare");
			}
		});
		Set<PromptVar> unused = new HashSet<>(template.required());
		unused.addAll(template.optional());
		unused.removeAll(used);
		if (!unused.isEmpty()) {
			throw new IllegalStateException("declares " + keys(unused) + " but never uses them");
		}
	}

	private static Set<PromptVar> variables(String text) {
		Set<PromptVar> result = new HashSet<>();
		for (String name : PromptText.variableNames(text)) {
			PromptVar variable = BY_KEY.get(name);
			if (variable == null) {
				throw new IllegalStateException("unknown variable {{" + name + "}}");
			}
			result.add(variable);
		}
		return result;
	}

	private static String keys(Set<PromptVar> variables) {
		return variables.stream().map(PromptVar::key).sorted().collect(Collectors.joining(", "));
	}

	private static String readClasspath(String path) {
		try (InputStream in = PromptLibrary.class.getClassLoader().getResourceAsStream(ROOT + path)) {
			if (in == null) {
				throw new IllegalStateException("missing classpath:" + ROOT + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
