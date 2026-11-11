package com.linguaflow.ai.usage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The LLM call log. A user's own figures are always scoped by {@code userId}; the admin
 * summary is system-wide and says so in its name.
 */
public interface AiRequestRepository extends OwnedRepository<AiRequest> {

	/** A user's calls and tokens since {@code since}, for the daily quota. */
	record UserUsage(long requests, long tokens) {
	}

	@Query("""
			select new com.linguaflow.ai.usage.AiRequestRepository$UserUsage(count(r),
			       coalesce(sum(r.inputTokens), 0) + coalesce(sum(r.outputTokens), 0))
			from AiRequest r
			where r.userId = :userId and r.createdAt >= :since""")
	UserUsage usageByUserIdSince(@Param("userId") UUID userId, @Param("since") Instant since);

	/** One summary row: totals for a UTC day, use case and model. */
	interface UsageRow {

		java.time.LocalDate getDay();

		String getUseCase();

		String getModel();

		long getRequests();

		long getFailures();

		long getInputTokens();

		long getOutputTokens();

		java.math.BigDecimal getCost();

	}

	@Query(value = """
			select cast(created_at at time zone 'UTC' as date) as day, use_case as useCase, model,
			       count(*) as requests, count(*) filter (where not success) as failures,
			       coalesce(sum(input_tokens), 0) as inputTokens, coalesce(sum(output_tokens), 0) as outputTokens,
			       coalesce(sum(cost_estimate_usd), 0) as cost
			from ai_requests
			where created_at >= :from and created_at < :to
			group by 1, 2, 3
			order by 1, 2, 3""", nativeQuery = true)
	List<UsageRow> summarizeForAdmin(@Param("from") Instant from, @Param("to") Instant to);

	/** The users with the highest estimated cost in the period. */
	interface UserCostRow {

		UUID getUserId();

		long getRequests();

		java.math.BigDecimal getCost();

	}

	@Query(value = """
			select user_id as userId, count(*) as requests, coalesce(sum(cost_estimate_usd), 0) as cost
			from ai_requests
			where created_at >= :from and created_at < :to and user_id is not null
			group by user_id
			order by cost desc, requests desc
			limit :limit""", nativeQuery = true)
	List<UserCostRow> topUsersForAdmin(@Param("from") Instant from, @Param("to") Instant to,
			@Param("limit") int limit);

}
