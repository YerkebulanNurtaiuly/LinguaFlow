package com.linguaflow.vocabulary;

import java.util.UUID;

import com.linguaflow.common.persistence.OwnedRepository;
import com.linguaflow.testsupport.OwnedRepositoryContract;
import com.linguaflow.testsupport.OwnedRepositoryTest;
import org.springframework.beans.factory.annotation.Autowired;

@OwnedRepositoryTest
class VocabularyItemRepositoryTests extends OwnedRepositoryContract<VocabularyItem> {

	@Autowired
	private VocabularyItemRepository items;

	@Override
	protected OwnedRepository<VocabularyItem> repository() {
		return items;
	}

	@Override
	protected VocabularyItem newEntity(UUID userId, int n) {
		return new VocabularyItem(userId, "de", "Wort " + n, "wort " + n);
	}

}
