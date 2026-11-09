package com.linguaflow.learning;

import java.time.LocalDate;
import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;
import com.linguaflow.testsupport.OwnedRepositoryContract;
import com.linguaflow.testsupport.OwnedRepositoryTest;
import org.springframework.beans.factory.annotation.Autowired;

@OwnedRepositoryTest
class StudySessionRepositoryTests extends OwnedRepositoryContract<StudySession> {

	@Autowired
	private StudySessionRepository sessions;

	@Override
	protected OwnedRepository<StudySession> repository() {
		return sessions;
	}

	@Override
	protected StudySession newEntity(UUID userId, int n) {
		return new StudySession(userId, "de", "Lektion " + n, LocalDate.of(2026, 11, 9));
	}

}
