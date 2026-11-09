package com.linguaflow.vocabulary;

import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;
import com.linguaflow.testsupport.OwnedRepositoryContract;
import com.linguaflow.testsupport.OwnedRepositoryTest;
import org.springframework.beans.factory.annotation.Autowired;

@OwnedRepositoryTest
class TagRepositoryTests extends OwnedRepositoryContract<Tag> {

	@Autowired
	private TagRepository tags;

	@Override
	protected OwnedRepository<Tag> repository() {
		return tags;
	}

	@Override
	protected Tag newEntity(UUID userId, int n) {
		return new Tag(userId, "de", "Tag " + n);
	}

}
