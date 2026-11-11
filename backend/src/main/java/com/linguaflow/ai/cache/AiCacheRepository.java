package com.linguaflow.ai.cache;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The shared AI cache (AI-06). Not user-owned: entries hold language data only, so any
 * user may read any entry.
 */
public interface AiCacheRepository extends Repository<AiCacheEntry, String> {

	Optional<AiCacheEntry> findById(String cacheKey);

	/** Stores a result, replacing an older one under the same key (e.g. an expired entry). */
	@Modifying
	@Query(value = """
			insert into ai_cache (cache_key, use_case, language_code, prompt_version, payload, created_at, hit_count)
			values (:key, :useCase, :languageCode, :promptVersion, cast(:payload as jsonb), :now, 0)
			on conflict (cache_key) do update
			set payload = excluded.payload, created_at = excluded.created_at, last_hit_at = null, hit_count = 0""",
			nativeQuery = true)
	void upsert(@Param("key") String key, @Param("useCase") String useCase, @Param("languageCode") String languageCode,
			@Param("promptVersion") String promptVersion, @Param("payload") String payload, @Param("now") Instant now);

	@Modifying
	@Query(value = "update ai_cache set hit_count = hit_count + 1, last_hit_at = :now where cache_key = :key",
			nativeQuery = true)
	void recordHit(@Param("key") String key, @Param("now") Instant now);

}
