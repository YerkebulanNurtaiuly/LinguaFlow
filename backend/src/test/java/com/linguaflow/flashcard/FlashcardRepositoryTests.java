package com.linguaflow.flashcard;

import java.util.UUID;

import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.persistence.OwnedRepository;
import com.linguaflow.testsupport.OwnedRepositoryContract;
import com.linguaflow.testsupport.OwnedRepositoryTest;
import org.springframework.beans.factory.annotation.Autowired;

@OwnedRepositoryTest
class FlashcardRepositoryTests extends OwnedRepositoryContract<Flashcard> {

	@Autowired
	private FlashcardRepository cards;

	@Override
	protected OwnedRepository<Flashcard> repository() {
		return cards;
	}

	@Override
	protected Flashcard newEntity(UUID userId, int n) {
		return new Flashcard(userId, insertWord(jdbc, userId, "wort" + n), CardDirection.TARGET_TO_NATIVE);
	}

	static UUID insertWord(org.springframework.jdbc.core.JdbcTemplate jdbc, UUID userId, String lemma) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma) "
				+ "values (?, ?, 'de', ?, ?)", id, userId, lemma, lemma);
		return id;
	}

}
