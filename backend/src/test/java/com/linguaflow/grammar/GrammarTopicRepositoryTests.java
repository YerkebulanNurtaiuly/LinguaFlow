package com.linguaflow.grammar;

import java.time.Instant;
import java.util.UUID;

import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.common.persistence.OwnedRepository;
import com.linguaflow.testsupport.OwnedRepositoryContract;
import com.linguaflow.testsupport.OwnedRepositoryTest;
import org.springframework.beans.factory.annotation.Autowired;

@OwnedRepositoryTest
class GrammarTopicRepositoryTests extends OwnedRepositoryContract<GrammarTopic> {

	@Autowired
	private GrammarTopicRepository topics;

	@Override
	protected OwnedRepository<GrammarTopic> repository() {
		return topics;
	}

	@Override
	protected GrammarTopic newEntity(UUID userId, int n) {
		return new GrammarTopic(userId, "de", "Thema " + n, "thema " + n, KnowledgeStatus.NEW, Instant.now());
	}

}
