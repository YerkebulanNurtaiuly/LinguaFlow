package com.linguaflow.flashcard;

import java.util.UUID;

import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.persistence.OwnedRepository;
import com.linguaflow.testsupport.OwnedRepositoryContract;
import com.linguaflow.testsupport.OwnedRepositoryTest;
import org.springframework.beans.factory.annotation.Autowired;

@OwnedRepositoryTest
class SrsStateRepositoryTests extends OwnedRepositoryContract<SrsState> {

	@Autowired
	private SrsStateRepository states;

	@Autowired
	private FlashcardRepository cards;

	@Override
	protected OwnedRepository<SrsState> repository() {
		return states;
	}

	@Override
	protected SrsState newEntity(UUID userId, int n) {
		Flashcard card = cards.saveAndFlush(new Flashcard(userId,
				FlashcardRepositoryTests.insertWord(jdbc, userId, "wort" + n), CardDirection.NATIVE_TO_TARGET));
		return new SrsState(card);
	}

}
