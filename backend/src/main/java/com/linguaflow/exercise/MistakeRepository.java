package com.linguaflow.exercise;

import java.util.List;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;

/** Mistakes, always scoped by owner (AUTH-05). */
public interface MistakeRepository extends OwnedRepository<Mistake> {

	/** An attempt's mistakes, in the order they were found. */
	List<Mistake> findAllByUserIdAndAttemptIdOrderById(UUID userId, UUID attemptId);

}
