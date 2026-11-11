package com.linguaflow.ai;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deterministic {@link LlmClient} for tests and offline development (AI-01): no network,
 * no cost, the same answer for the same input.
 * <ul>
 * <li>With a JSON Schema it answers with the smallest JSON that fits it: required
 * properties only, the first enum value, the minimum number, "fake &lt;name&gt;" for
 * strings and arrays at their minimum length.</li>
 * <li>Without one it answers {@code [fake <use case>] <first line of the prompt>}.</li>
 * <li>Tests can script answers per use case ({@link #respondTo}), queue failures
 * ({@link #failNext}) and inspect every call ({@link #calls}).</li>
 * </ul>
 */
public class FakeLlmClient implements LlmClient {

	/** One recorded call. */
	public record Call(LlmPrompt prompt, @Nullable Map<String, Object> jsonSchema, LlmOptions options) {
	}

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final Map<AiUseCase, Function<LlmPrompt, String>> responders = new ConcurrentHashMap<>();

	private final Deque<LlmException> failures = new ArrayDeque<>();

	private final List<Call> calls = new CopyOnWriteArrayList<>();

	@Override
	public LlmResponse complete(LlmPrompt prompt, @Nullable Map<String, Object> jsonSchema, LlmOptions options) {
		calls.add(new Call(prompt, jsonSchema, options));
		LlmException failure;
		synchronized (failures) {
			failure = failures.poll();
		}
		if (failure != null) {
			throw failure;
		}
		Function<LlmPrompt, String> responder = responders.get(options.useCase());
		String text = responder != null ? responder.apply(prompt)
				: jsonSchema != null ? JSON.writeValueAsString(sample(jsonSchema, "value"))
						: "[fake " + options.useCase().configKey() + "] " + firstLine(prompt.user());
		return new LlmResponse(text, "fake-" + options.model(), tokens(prompt.system()) + tokens(prompt.user()),
				tokens(text), 0, Duration.ZERO);
	}

	@Override
	public String provider() {
		return "fake";
	}

	/** Answers calls for {@code useCase} with {@code responder} instead of the default. */
	public FakeLlmClient respondTo(AiUseCase useCase, Function<LlmPrompt, String> responder) {
		responders.put(useCase, responder);
		return this;
	}

	/** The next call fails with {@code failure} (queued in order). */
	public FakeLlmClient failNext(LlmException failure) {
		synchronized (failures) {
			failures.add(failure);
		}
		return this;
	}

	public List<Call> calls() {
		return List.copyOf(calls);
	}

	/** Forgets scripted answers, queued failures and recorded calls. */
	public void reset() {
		responders.clear();
		synchronized (failures) {
			failures.clear();
		}
		calls.clear();
	}

	/** The smallest value that fits {@code schema}; deterministic for the same schema. */
	@SuppressWarnings("unchecked")
	static Object sample(Map<String, Object> schema, String name) {
		Object enumValues = schema.get("enum");
		if (enumValues instanceof List<?> values && !values.isEmpty()) {
			return values.getFirst();
		}
		if (schema.containsKey("const")) {
			return schema.get("const");
		}
		return switch (type(schema)) {
			case "object" -> {
				Map<String, Object> result = new LinkedHashMap<>();
				Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
				List<String> required = (List<String>) schema.getOrDefault("required", List.copyOf(properties.keySet()));
				for (String property : required) {
					Object propertySchema = properties.get(property);
					result.put(property, propertySchema instanceof Map<?, ?> map
							? sample((Map<String, Object>) map, property) : null);
				}
				yield result;
			}
			case "array" -> {
				List<Object> result = new ArrayList<>();
				int min = schema.get("minItems") instanceof Number n ? n.intValue() : 0;
				Map<String, Object> items = schema.get("items") instanceof Map<?, ?> map ? (Map<String, Object>) map
						: Map.of("type", "string");
				for (int i = 0; i < min; i++) {
					result.add(sample(items, name + " " + (i + 1)));
				}
				yield result;
			}
			case "integer" -> schema.get("minimum") instanceof Number n ? n.longValue() : 0L;
			case "number" -> schema.get("minimum") instanceof Number n ? n.doubleValue() : 0.0;
			case "boolean" -> false;
			case "null" -> null;
			default -> "fake " + name;
		};
	}

	/** The schema's type; for a list like ["string", "null"], the first non-null one. */
	private static String type(Map<String, Object> schema) {
		Object type = schema.get("type");
		if (type instanceof List<?> types) {
			return types.stream().map(String::valueOf).filter(t -> !t.equals("null")).findFirst().orElse("null");
		}
		if (type != null) {
			return String.valueOf(type);
		}
		return schema.containsKey("properties") ? "object" : "string";
	}

	private static String firstLine(String text) {
		String line = text.strip().lines().findFirst().orElse("");
		return line.length() > 80 ? line.substring(0, 80) + "…" : line;
	}

	/** Roughly four characters per token, like the providers' rule of thumb. */
	private static long tokens(String text) {
		return (text.length() + 3) / 4;
	}

}
