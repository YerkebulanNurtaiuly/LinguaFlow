package com.linguaflow.ai.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

/**
 * One LLM call, successful or not (AI-05). Append-only, and it never holds the prompt or
 * the answer, which contain the learner's material (SEC-02): only what was called, by
 * whom, how big and how expensive it was.
 */
@Entity
@Table(name = "ai_requests")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiRequest implements OwnedEntity {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "user_id", updatable = false)
	private @Nullable UUID userId;

	@Column(name = "job_id", updatable = false)
	private @Nullable UUID jobId;

	/** An {@code AiUseCase} name. */
	@Column(name = "use_case", nullable = false, updatable = false)
	private String useCase;

	/** The model that answered, or the requested one when the call failed. */
	@Column(nullable = false, updatable = false)
	private String model;

	/** A prompt template's version tag, e.g. {@code vocabulary-enrichment/v1}. */
	@Column(name = "prompt_version", nullable = false, updatable = false)
	private String promptVersion;

	/** Every input token, cached or not; null when the call failed. */
	@Column(name = "input_tokens", updatable = false)
	private @Nullable Integer inputTokens;

	@Column(name = "output_tokens", updatable = false)
	private @Nullable Integer outputTokens;

	@Column(name = "latency_ms", updatable = false)
	private @Nullable Integer latencyMs;

	/** Null when the model's price is unknown. */
	@Column(name = "cost_estimate_usd", precision = 10, scale = 6, updatable = false)
	private @Nullable BigDecimal costEstimateUsd;

	@Column(nullable = false, updatable = false)
	private boolean success;

	/** The {@code LlmException.Kind} of a failed call. */
	@Column(name = "error_code", updatable = false)
	private @Nullable String errorCode;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	AiRequest(@Nullable UUID userId, @Nullable UUID jobId, String useCase, String model, String promptVersion,
			@Nullable Integer inputTokens, @Nullable Integer outputTokens, @Nullable Integer latencyMs,
			@Nullable BigDecimal costEstimateUsd, boolean success, @Nullable String errorCode, Instant createdAt) {
		this.userId = userId;
		this.jobId = jobId;
		this.useCase = useCase;
		this.model = model;
		this.promptVersion = promptVersion;
		this.inputTokens = inputTokens;
		this.outputTokens = outputTokens;
		this.latencyMs = latencyMs;
		this.costEstimateUsd = costEstimateUsd;
		this.success = success;
		this.errorCode = errorCode;
		this.createdAt = createdAt;
	}

}
