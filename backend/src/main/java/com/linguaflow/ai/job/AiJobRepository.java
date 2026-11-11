package com.linguaflow.ai.job;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * AI jobs. Users see their own through the {@link OwnedRepository} methods (AI-08); the
 * worker's system-wide methods are named {@code ...ForProcessing...}.
 */
public interface AiJobRepository extends OwnedRepository<AiJob> {

	/**
	 * Locks up to {@code limit} due jobs, oldest first. Rows another worker has locked are
	 * skipped, so two workers never claim the same job. Must run in a transaction.
	 */
	@Query(value = """
			select id from ai_jobs
			where status = 'QUEUED' and next_run_at <= :now
			order by next_run_at, id
			limit :limit
			for update skip locked""", nativeQuery = true)
	List<UUID> lockDueForProcessing(@Param("now") Instant now, @Param("limit") int limit);

	/** Locks jobs RUNNING since before {@code before}: their worker stopped. */
	@Query(value = """
			select id from ai_jobs
			where status = 'RUNNING' and locked_at < :before
			order by locked_at
			limit :limit
			for update skip locked""", nativeQuery = true)
	List<UUID> lockStaleForProcessing(@Param("before") Instant before, @Param("limit") int limit);

	@Query("select j from AiJob j where j.id in :ids order by j.nextRunAt, j.id")
	List<AiJob> findAllForProcessingByIdIn(@Param("ids") Collection<UUID> ids);

	@Query("select j from AiJob j where j.id = :id")
	Optional<AiJob> findForProcessingById(@Param("id") UUID id);

	/** The user's jobs, filtered (AI-08). */
	Page<AiJob> findByUserIdAndStatusInAndTypeIn(UUID userId, Collection<AiJobStatus> statuses,
			Collection<AiJobType> types, Pageable pageable);

	/** A job's ID and status. */
	interface JobStatus {

		UUID getId();

		AiJobStatus getStatus();

	}

	/** The status of each of the user's jobs among {@code ids}; others' jobs are left out. */
	@Query("select j.id as id, j.status as status from AiJob j where j.userId = :userId and j.id in :ids")
	List<JobStatus> findStatusesByUserIdAndIdIn(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);

}
