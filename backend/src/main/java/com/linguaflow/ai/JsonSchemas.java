package com.linguaflow.ai;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

/**
 * Builds the JSON Schema of an answer record for structured output (AI-02).
 * <ul>
 * <li>A record becomes an object whose components are all {@code required}, with
 * {@code additionalProperties: false}. A component marked {@code @Nullable} may be
 * {@code null} (as {@code anyOf: [schema, {type: null}]}, the one form the API takes for
 * enums too); every other one must be present.</li>
 * <li>{@code String}, boxed numbers, {@code Boolean}, enums (by name), {@code List} or
 * {@code Set} and nested records are supported. Primitives are refused, because a
 * missing primitive would silently parse as 0 or false; use the boxed type.</li>
 * <li>{@code @JsonPropertyDescription} and {@code @JsonClassDescription} become
 * {@code description}s, which the model reads.</li>
 * <li>Only keywords structured output accepts are emitted. Lengths, ranges and
 * patterns stay in Bean Validation on the record; the retry with feedback enforces
 * them.</li>
 * </ul>
 */
final class JsonSchemas {

	private JsonSchemas() {
	}

	static Map<String, Object> forRecord(Class<?> type) {
		if (!type.isRecord()) {
			throw new IllegalArgumentException(type.getName() + " is not a record");
		}
		return objectSchema(type, new HashSet<>());
	}

	private static Map<String, Object> objectSchema(Class<?> type, Set<Class<?>> visiting) {
		if (!visiting.add(type)) {
			throw new IllegalArgumentException("Recursive answer types aren't supported: " + type.getName());
		}
		Map<String, Object> properties = new LinkedHashMap<>();
		for (RecordComponent component : type.getRecordComponents()) {
			Map<String, Object> schema = schema(component.getGenericType(), nullable(component),
					type.getSimpleName() + "." + component.getName(), visiting);
			JsonPropertyDescription description = component.getAccessor().getAnnotation(JsonPropertyDescription.class);
			if (description != null && !description.value().isBlank()) {
				schema.put("description", description.value());
			}
			properties.put(component.getName(), schema);
		}
		visiting.remove(type);
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		JsonClassDescription description = type.getAnnotation(JsonClassDescription.class);
		if (description != null && !description.value().isBlank()) {
			schema.put("description", description.value());
		}
		schema.put("properties", properties);
		schema.put("required", List.copyOf(properties.keySet()));
		schema.put("additionalProperties", false);
		return schema;
	}

	private static Map<String, Object> schema(Type type, boolean nullable, String where, Set<Class<?>> visiting) {
		if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw
				&& Collection.class.isAssignableFrom(raw)) {
			if (!List.class.isAssignableFrom(raw) && !Set.class.isAssignableFrom(raw)) {
				throw unsupported(where, type);
			}
			Map<String, Object> schema = new LinkedHashMap<>();
			schema.put("type", "array");
			schema.put("items", schema(parameterized.getActualTypeArguments()[0], false, where + "[]", visiting));
			return nullable ? orNull(schema) : schema;
		}
		if (!(type instanceof Class<?> cls)) {
			throw unsupported(where, type);
		}
		if (cls.isPrimitive()) {
			throw new IllegalArgumentException(
					where + ": use the boxed type instead of " + cls.getName() + ", so a missing value is noticed");
		}
		if (cls.isEnum()) {
			Map<String, Object> schema = scalar("string", false);
			schema.put("enum", Arrays.stream(cls.getEnumConstants()).map(c -> ((Enum<?>) c).name()).toList());
			return nullable ? orNull(schema) : schema;
		}
		if (cls.isRecord()) {
			Map<String, Object> schema = objectSchema(cls, visiting);
			return nullable ? orNull(schema) : schema;
		}
		if (cls == String.class) {
			return scalar("string", nullable);
		}
		if (cls == Integer.class || cls == Long.class || cls == Short.class) {
			return scalar("integer", nullable);
		}
		if (cls == Double.class || cls == Float.class || cls == BigDecimal.class) {
			return scalar("number", nullable);
		}
		if (cls == Boolean.class) {
			return scalar("boolean", nullable);
		}
		throw unsupported(where, type);
	}

	private static Map<String, Object> scalar(String type, boolean nullable) {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", type);
		return nullable ? orNull(schema) : schema;
	}

	private static Map<String, Object> orNull(Map<String, Object> schema) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("anyOf", List.of(schema, Map.of("type", "null")));
		return result;
	}

	static boolean nullable(RecordComponent component) {
		return component.getAnnotatedType().isAnnotationPresent(Nullable.class);
	}

	private static IllegalArgumentException unsupported(String where, Type type) {
		return new IllegalArgumentException(where + ": type " + type.getTypeName() + " isn't supported in AI answers");
	}

}
