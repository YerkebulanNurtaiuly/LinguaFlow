package com.linguaflow.ai.job;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;
import com.linguaflow.testsupport.OwnedRepositoryContract;
import com.linguaflow.testsupport.OwnedRepositoryTest;
import org.springframework.beans.factory.annotation.Autowired;

@OwnedRepositoryTest
class AiJobRepositoryTests extends OwnedRepositoryContract<AiJob> {

	@Autowired
	private AiJobRepository jobs;

	@Override
	protected OwnedRepository<AiJob> repository() {
		return jobs;
	}

	@Override
	protected AiJob newEntity(UUID userId, int n) {
		return new AiJob(AiJobType.VOCABULARY_ENRICHMENT, userId, Map.of("n", n), 3, Instant.now());
	}

}
