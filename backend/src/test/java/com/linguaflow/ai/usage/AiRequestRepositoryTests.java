package com.linguaflow.ai.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;
import com.linguaflow.testsupport.OwnedRepositoryContract;
import com.linguaflow.testsupport.OwnedRepositoryTest;
import org.springframework.beans.factory.annotation.Autowired;

@OwnedRepositoryTest
class AiRequestRepositoryTests extends OwnedRepositoryContract<AiRequest> {

	@Autowired
	private AiRequestRepository requests;

	@Override
	protected OwnedRepository<AiRequest> repository() {
		return requests;
	}

	@Override
	protected AiRequest newEntity(UUID userId, int n) {
		return new AiRequest(userId, null, "VOCABULARY_ENRICHMENT", "claude-opus-5-5", "vocabulary-enrichment/v1",
				100 + n, 50, 1200, new BigDecimal("0.001400"), true, null, Instant.now());
	}

}
