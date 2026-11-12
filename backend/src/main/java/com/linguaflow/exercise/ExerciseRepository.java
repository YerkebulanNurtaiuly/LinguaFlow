package com.linguaflow.exercise;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Exercises, always scoped by owner (AUTH-05). */
public interface ExerciseRepository extends OwnedRepository<Exercise> {

	/** A set's exercises in order. */
	List<Exercise> findAllByUserIdAndExerciseSetIdOrderByPosition(UUID userId, UUID exerciseSetId);

	/** How many exercises each of the user's sets among {@code setIds} has (EX-02). */
	@Query("""
			select e.exerciseSetId as setId, count(e) as count from Exercise e
			where e.userId = :userId and e.exerciseSetId in :setIds
			group by e.exerciseSetId""")
	List<SetCount> countBySets(@Param("userId") UUID userId, @Param("setIds") Collection<UUID> setIds);

	interface SetCount {

		UUID getSetId();

		long getCount();

	}

	/**
	 * The user's latest exercises on any of {@code grammarTopicIds}, newest first, so new
	 * ones don't repeat them (EX-02).
	 */
	@Query("""
			select e from Exercise e
			where e.userId = :userId and exists (
			  select 1 from Exercise linked join linked.grammarTopicIds topic
			  where linked = e and topic in :grammarTopicIds)
			order by e.createdAt desc, e.id desc""")
	List<Exercise> findRecentOnTopics(@Param("userId") UUID userId,
			@Param("grammarTopicIds") Collection<UUID> grammarTopicIds, Pageable page);

}
