package com.linguaflow.common.persistence;

import java.util.Optional;
import java.util.UUID;

import com.linguaflow.common.error.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Base repository for user-owned entities. It deliberately extends the bare
 * {@link Repository} instead of {@code JpaRepository}: there is no unscoped
 * {@code findById}, {@code findAll} or {@code deleteById}, so a service cannot forget the
 * owner. Every read and delete takes the current user's ID (from {@code @CurrentUserId}).
 * <p>
 * Another user's resource is indistinguishable from a missing one:
 * {@link #requireOwned} throws the same 404 for both, so IDs cannot be probed.
 * <p>
 * Module repositories add their own scoped queries (always with {@code userId}); a
 * genuinely system-wide lookup (e.g. an AI worker loading a job's item) must be a
 * separate, explicitly named method such as {@code findForProcessingById}.
 */
@NoRepositoryBean
public interface OwnedRepository<T extends OwnedEntity> extends Repository<T, UUID> {

	<S extends T> S save(S entity);

	<S extends T> S saveAndFlush(S entity);

	Optional<T> findByIdAndUserId(UUID id, UUID userId);

	boolean existsByIdAndUserId(UUID id, UUID userId);

	Page<T> findAllByUserId(UUID userId, Pageable pageable);

	long countByUserId(UUID userId);

	/** Deletes the entity only if the user owns it; returns how many rows were deleted (0 or 1). */
	@Transactional
	long deleteByIdAndUserId(UUID id, UUID userId);

	/**
	 * The user's entity, or 404 if it does not exist <em>or belongs to someone else</em>
	 * (same response for both).
	 */
	default T requireOwned(UUID id, UUID userId) {
		return findByIdAndUserId(id, userId).orElseThrow(() -> new NotFoundException("Resource not found"));
	}

}
