package com.linguaflow.ai;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.linguaflow.ai.prompt.RenderedPrompt;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks the model for JSON in the shape of a Java record and only returns answers that
 * pass every check (AI-02):
 * <ol>
 * <li>The record's JSON Schema ({@link JsonSchemas}) goes with the call as structured
 * output.</li>
 * <li>The answer is parsed into the record; unknown properties are ignored.</li>
 * <li>Every component not marked {@code @Nullable} must be present, also in nested
 * records and list elements.</li>
 * <li>Bean Validation runs on the record; mark nested records {@code @Valid}, in lists
 * as {@code List<@Valid Example>}.</li>
 * <li>The caller's {@link OutputCheck} runs domain rules, e.g. article ∈ {der, die,
 * das}.</li>
 * </ol>
 * An answer that fails is retried once: the same system prompt (so it stays cached),
 * and the user prompt followed by the rejected answer and its problems. If the second
 * answer fails too, {@link InvalidLlmOutputException} is thrown. Provider errors
 * ({@link LlmException}) pass through untouched; retrying them is the job queue's call
 * (AI-04).
 */
@Component
public class StructuredLlm {

	private static final Logger log = LoggerFactory.getLogger(StructuredLlm.class);

	/** How many calls one answer may take: the first and one retry with feedback. */
	static final int MAX_ATTEMPTS = 2;

	/** How much of a rejected answer is quoted back on the retry. */
	static final int MAX_QUOTED_ANSWER = 4_000;

	private static final JsonMapper JSON = JsonMapper.builder()
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.build();

	private final LlmClient client;

	private final AiSettings settings;

	private final Validator validator;

	private final Map<Class<?>, Map<String, Object>> schemas = new ConcurrentHashMap<>();

	public StructuredLlm(LlmClient client, AiSettings settings, Validator validator) {
		this.client = client;
		this.settings = settings;
		this.validator = validator;
	}

	public <T> StructuredResponse<T> complete(AiUseCase useCase, LlmPrompt prompt, Class<T> type) {
		return complete(useCase, prompt, type, OutputCheck.none());
	}

	/** A prompt from the {@link com.linguaflow.ai.prompt.PromptLibrary} (AI-03), with its use case. */
	public <T> StructuredResponse<T> complete(RenderedPrompt prompt, Class<T> type, OutputCheck<? super T> check) {
		return complete(prompt.useCase(), prompt.prompt(), type, check);
	}

	/**
	 * @throws InvalidLlmOutputException when the answer is still invalid after one retry
	 * @throws LlmException when the provider gives no answer
	 */
	public <T> StructuredResponse<T> complete(AiUseCase useCase, LlmPrompt prompt, Class<T> type,
			OutputCheck<? super T> check) {
		Map<String, Object> schema = schemas.computeIfAbsent(type, JsonSchemas::forRecord);
		LlmOptions options = settings.options(useCase);
		List<LlmResponse> calls = new ArrayList<>();
		LlmPrompt current = prompt;
		List<String> problems = List.of();
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			LlmResponse response = client.complete(current, schema, options);
			calls.add(response);
			OutputProblems found = new OutputProblems();
			T value = parse(response.text(), type, found);
			if (value != null) {
				requirePresent(value, "", found);
				if (found.isEmpty()) {
					validate(value, found);
				}
				if (found.isEmpty()) {
					check.check(value, found);
				}
				if (found.isEmpty()) {
					return new StructuredResponse<>(value, calls);
				}
			}
			problems = found.list();
			log.warn("AI {} answer rejected (attempt {} of {}): {}", useCase.configKey(), attempt, MAX_ATTEMPTS,
					String.join("; ", problems));
			current = withFeedback(prompt, response.text(), problems);
		}
		throw new InvalidLlmOutputException(
				"The " + useCase.configKey() + " answer was invalid after " + MAX_ATTEMPTS + " attempts", problems,
				calls);
	}

	/** The JSON Schema sent for {@code type}; for tests and prompt templates (AI-03). */
	public Map<String, Object> schemaFor(Class<?> type) {
		return schemas.computeIfAbsent(type, JsonSchemas::forRecord);
	}

	private static <T> @Nullable T parse(String text, Class<T> type, OutputProblems problems) {
		String json = stripCodeFence(text);
		if (json.isEmpty()) {
			problems.add("", "the answer was empty; reply with a JSON object");
			return null;
		}
		try {
			T value = JSON.readValue(json, type);
			if (value == null) {
				problems.add("", "the answer was null; reply with a JSON object");
			}
			return value;
		}
		catch (JacksonException ex) {
			String path = jsonPath(ex);
			String message = QUALIFIED_NAME
				.matcher(ex.getOriginalMessage().lines().findFirst().orElse("is not valid JSON"))
				.replaceAll("$1");
			problems.add("", "the answer isn't valid JSON for the schema" + (path.isEmpty() ? "" : " at " + path)
					+ ": " + message);
			return null;
		}
	}

	/** {@code com.linguaflow.ai.Foo$Gender} → {@code Gender}, so no Java internals reach the model. */
	private static final Pattern QUALIFIED_NAME = Pattern.compile("(?:[a-z_][\\w]*\\.)+(?:\\w+\\$)*(\\w+)");

	/** Where parsing failed, as {@code examples[0].sentence}. */
	private static String jsonPath(JacksonException ex) {
		StringBuilder path = new StringBuilder();
		for (JacksonException.Reference reference : ex.getPath()) {
			if (reference.getPropertyName() != null) {
				path.append(path.isEmpty() ? "" : ".").append(reference.getPropertyName());
			}
			else if (reference.getIndex() >= 0) {
				path.append('[').append(reference.getIndex()).append(']');
			}
		}
		return path.toString();
	}

	/** Some models wrap JSON in a Markdown fence when no schema is enforced. */
	private static String stripCodeFence(String text) {
		String json = text.strip();
		if (json.startsWith("```")) {
			int start = json.indexOf('\n');
			int end = json.lastIndexOf("```");
			if (start > 0 && end > start) {
				json = json.substring(start + 1, end).strip();
			}
		}
		return json;
	}

	/** Components not marked {@code @Nullable} must be present, at any depth. */
	private static void requirePresent(Object value, String path, OutputProblems problems) {
		if (!value.getClass().isRecord()) {
			return;
		}
		for (RecordComponent component : value.getClass().getRecordComponents()) {
			String where = path.isEmpty() ? component.getName() : path + "." + component.getName();
			Object field;
			try {
				field = component.getAccessor().invoke(value);
			}
			catch (ReflectiveOperationException ex) {
				throw new IllegalStateException("Can't read " + where, ex);
			}
			if (field == null) {
				if (!JsonSchemas.nullable(component)) {
					problems.add(where, "is missing");
				}
				continue;
			}
			if (field instanceof Collection<?> elements) {
				int i = 0;
				for (Object element : elements) {
					String at = where + "[" + i++ + "]";
					if (element == null) {
						problems.add(at, "must not be null");
					}
					else {
						requirePresent(element, at, problems);
					}
				}
			}
			else {
				requirePresent(field, where, problems);
			}
		}
	}

	private void validate(Object value, OutputProblems problems) {
		validator.validate(value)
			.stream()
			.sorted(Comparator.comparing((ConstraintViolation<Object> v) -> v.getPropertyPath().toString())
				.thenComparing(ConstraintViolation::getMessage))
			.forEach(v -> problems.add(v.getPropertyPath().toString(), v.getMessage()));
	}

	/**
	 * The retry prompt: the original request, the rejected answer and what to fix. The
	 * system prompt is unchanged, so its cache entry is reused.
	 */
	static LlmPrompt withFeedback(LlmPrompt prompt, String answer, List<String> problems) {
		String quoted = answer.length() > MAX_QUOTED_ANSWER ? answer.substring(0, MAX_QUOTED_ANSWER) + "…" : answer;
		StringBuilder user = new StringBuilder(prompt.user().strip()).append("\n\n---\n")
			.append("Your previous answer was rejected:\n")
			.append(quoted.isBlank() ? "(empty)" : quoted)
			.append("\n\nProblems:\n");
		problems.forEach(problem -> user.append("- ").append(problem).append('\n'));
		user.append("\nAnswer again with the complete corrected JSON, fixing every problem.");
		return new LlmPrompt(prompt.system(), user.toString());
	}

}
