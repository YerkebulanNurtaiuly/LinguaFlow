package com.linguaflow.flashcard;

import java.time.Instant;
import java.util.UUID;

import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.persistence.OwnedRepository;
import com.linguaflow.testsupport.OwnedRepositoryContract;
import com.linguaflow.testsupport.OwnedRepositoryTest;
import org.springframework.beans.factory.annotation.Autowired;

@OwnedRepositoryTest
class FlashcardReviewRepositoryTests extends OwnedRepositoryContract<FlashcardReview> {

	@Autowired
	private FlashcardReviewRepository reviews;

	@Autowired
	private FlashcardRepository cards;

	@Override
	protected OwnedRepository<FlashcardReview> repository() {
		return reviews;
	}

	@Override
	protected FlashcardReview newEntity(UUID userId, int n) {
		Flashcard card = cards.saveAndFlush(new Flashcard(userId,
				FlashcardRepositoryTests.insertWord(jdbc, userId, "wort" + n), CardDirection.TARGET_TO_NATIVE));
		SrsScheduler.Card before = SrsScheduler.Card.fresh();
		Instant now = Instant.parse("2026-11-11T10:00:00Z");
		return new FlashcardReview(card, UUID.randomUUID(), ReviewGrade.GOOD, now, 1500, before,
				new SrsScheduler().review(before, ReviewGrade.GOOD, now, java.time.ZoneOffset.UTC));
	}

}
