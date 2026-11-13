package com.linguaflow.progress;

import java.util.List;

import com.linguaflow.exercise.ExerciseAttemptedEvent;
import com.linguaflow.flashcard.FlashcardReviewedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the progress aggregates current (PRG-01). It acts once a review or attempt is
 * committed, in a transaction of its own, so a failure here never loses the learner's
 * answer: it's logged, and the startup rebuild repairs the rows.
 */
@Component
class ProgressEventHandler {

	private static final Logger log = LoggerFactory.getLogger(ProgressEventHandler.class);

	private final ProgressAggregates aggregates;

	private final MasteryService mastery;

	private final WeakPointService weakPoints;

	private final TransactionTemplate tx;

	ProgressEventHandler(ProgressAggregates aggregates, MasteryService mastery, WeakPointService weakPoints,
			PlatformTransactionManager transactions) {
		this.weakPoints = weakPoints;
		this.aggregates = aggregates;
		this.mastery = mastery;
		this.tx = new TransactionTemplate(transactions);
		// Its own transaction: the answer's is already committed
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@TransactionalEventListener
	public void onAttempt(ExerciseAttemptedEvent event) {
		try {
			tx.executeWithoutResult(status -> {
				aggregates.refreshTopics(event.userId(), event.grammarTopicIds());
				aggregates.refreshWords(event.userId(), event.vocabularyItemIds());
				aggregates.refreshMistakeCounts(event.userId(), event.languageCode());
				weakPoints.updateScores(event.userId(), event.grammarTopicIds(), event.vocabularyItemIds());
				mastery.apply(event.userId(), event.grammarTopicIds(), event.vocabularyItemIds());
			});
		}
		catch (RuntimeException ex) {
			log.warn("Couldn't update progress after attempt {}", event.attemptId(), ex);
		}
	}

	@TransactionalEventListener
	public void onReview(FlashcardReviewedEvent event) {
		try {
			tx.executeWithoutResult(status -> {
				aggregates.refreshWords(event.userId(), List.of(event.vocabularyItemId()));
				weakPoints.updateScores(event.userId(), List.of(), List.of(event.vocabularyItemId()));
				mastery.apply(event.userId(), List.of(), List.of(event.vocabularyItemId()));
			});
		}
		catch (RuntimeException ex) {
			log.warn("Couldn't update progress after review {}", event.reviewId(), ex);
		}
	}

	/**
	 * Rebuilds every learner's rows at startup: it fills them for answers from before
	 * PRG-01 and repairs any that drifted (a failed update, a deleted exercise set).
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void rebuildAtStartup() {
		int users = 0;
		for (var userId : aggregates.usersWithAnswers()) {
			try {
				tx.executeWithoutResult(status -> {
					aggregates.rebuild(userId);
					weakPoints.updateScores(userId, null, null);
					mastery.apply(userId, null, null);
				});
				users++;
			}
			catch (RuntimeException ex) {
				log.warn("Couldn't rebuild progress for user {}", userId, ex);
			}
		}
		if (users > 0) {
			log.info("Rebuilt progress for {} learners", users);
		}
	}

}
