package com.linguaflow.learning.practice;

import java.time.Instant;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;
import com.linguaflow.testsupport.OwnedRepositoryContract;
import com.linguaflow.testsupport.OwnedRepositoryTest;
import org.springframework.beans.factory.annotation.Autowired;

@OwnedRepositoryTest
class PracticeSessionRepositoryTests extends OwnedRepositoryContract<PracticeSession> {

	@Autowired
	private PracticeSessionRepository sessions;

	@Override
	protected OwnedRepository<PracticeSession> repository() {
		return sessions;
	}

	@Override
	protected PracticeSession newEntity(UUID userId, int n) {
		return new PracticeSession(userId, "de", PracticeMode.MIXED, null,
				new PracticeConfig(10 + n, true, true, 0.6, n), Instant.parse("2026-11-12T10:00:00Z"));
	}

}
