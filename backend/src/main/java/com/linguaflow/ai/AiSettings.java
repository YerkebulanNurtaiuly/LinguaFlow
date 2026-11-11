package com.linguaflow.ai;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.linguaflow.ai.LlmOptions.Effort;
import com.linguaflow.common.config.LinguaFlowProperties;
import org.springframework.stereotype.Component;

/**
 * The {@link LlmOptions} of each use case: built-in defaults, overridden by
 * {@code linguaflow.ai.use-cases.<use-case>.*}. Unknown use-case keys and effort values
 * fail at startup rather than at the first call.
 * <p>
 * Effort follows how much reasoning a use case needs: extracting word data and short
 * feedback run at {@code low}, explanations and exercise writing at {@code medium}. The
 * output limit counts the model's reasoning too, so it is generous.
 */
@Component
public class AiSettings {

	private static final Map<AiUseCase, Defaults> DEFAULTS = Map.of(
			AiUseCase.VOCABULARY_ENRICHMENT, new Defaults(Effort.LOW, 16_000, Duration.ofMinutes(2)),
			AiUseCase.GRAMMAR_EXPLANATION, new Defaults(Effort.MEDIUM, 16_000, Duration.ofMinutes(2)),
			AiUseCase.EXERCISE_GENERATION, new Defaults(Effort.MEDIUM, 16_000, Duration.ofMinutes(3)),
			AiUseCase.ANSWER_EVALUATION, new Defaults(Effort.LOW, 4_000, Duration.ofMinutes(1)),
			AiUseCase.MISTAKE_EXPLANATION, new Defaults(Effort.LOW, 4_000, Duration.ofMinutes(1)));

	private record Defaults(Effort effort, int maxTokens, Duration timeout) {
	}

	private final Map<AiUseCase, LlmOptions> options = new EnumMap<>(AiUseCase.class);

	public AiSettings(LinguaFlowProperties properties) {
		LinguaFlowProperties.Ai ai = properties.ai();
		Map<String, AiUseCase> byKey = Arrays.stream(AiUseCase.values())
			.collect(Collectors.toMap(AiUseCase::configKey, useCase -> useCase));
		ai.useCases().keySet().forEach(key -> {
			if (!byKey.containsKey(key)) {
				throw new IllegalArgumentException("Unknown AI use case in linguaflow.ai.use-cases: '" + key
						+ "'; known: " + String.join(", ", byKey.keySet()));
			}
		});
		for (AiUseCase useCase : AiUseCase.values()) {
			Defaults defaults = DEFAULTS.get(useCase);
			LinguaFlowProperties.Ai.UseCase override = ai.useCases().get(useCase.configKey());
			options.put(useCase, new LlmOptions(useCase,
					override != null && override.model() != null && !override.model().isBlank()
							? override.model().strip() : ai.model(),
					override != null && override.effort() != null ? effort(useCase, override.effort())
							: defaults.effort(),
					override != null && override.maxTokens() != null ? override.maxTokens() : defaults.maxTokens(),
					override != null && override.timeout() != null ? override.timeout() : defaults.timeout()));
		}
	}

	public LlmOptions options(AiUseCase useCase) {
		return options.get(useCase);
	}

	private static Effort effort(AiUseCase useCase, String value) {
		try {
			return Effort.valueOf(value.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Unknown effort '" + value + "' for AI use case " + useCase.configKey()
					+ "; use low, medium, high, xhigh or max", ex);
		}
	}

}
