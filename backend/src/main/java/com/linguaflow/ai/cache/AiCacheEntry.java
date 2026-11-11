package com.linguaflow.ai.cache;

import java.time.Instant;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One cached AI result, shared between users (AI-06). It holds language data only, so it
 * isn't user-owned. Written with an upsert in {@link AiCacheRepository}, so this entity
 * is only read.
 */
@Entity
@Table(name = "ai_cache")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiCacheEntry {

	@Id
	@Column(name = "cache_key")
	private String cacheKey;

	@Column(name = "use_case", nullable = false)
	private String useCase;

	@Column(name = "language_code", nullable = false)
	private String languageCode;

	@Column(name = "prompt_version", nullable = false)
	private String promptVersion;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private Map<String, Object> payload;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "last_hit_at")
	private @Nullable Instant lastHitAt;

	@Column(name = "hit_count", nullable = false)
	private int hitCount;

}
