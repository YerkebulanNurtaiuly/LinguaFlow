package com.linguaflow.learning;

import com.linguaflow.grammar.GrammarTopicCreated;
import com.linguaflow.vocabulary.VocabularyItemCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Attaches new words and grammar topics to the learner's active study session (SES-01).
 * The material is already saved when the events arrive, so a failure here is logged
 * rather than undoing the learner's work.
 */
@Component
class ActiveSessionAttacher {

	private static final Logger log = LoggerFactory.getLogger(ActiveSessionAttacher.class);

	private final StudySessionService sessions;

	ActiveSessionAttacher(StudySessionService sessions) {
		this.sessions = sessions;
	}

	@EventListener
	void onVocabularyItemCreated(VocabularyItemCreated event) {
		try {
			sessions.attachToActive(event.userId(), event.languageCode(), event.vocabularyItemId(), null);
		}
		catch (RuntimeException ex) {
			log.warn("Couldn't attach word {} to the active study session", event.vocabularyItemId(), ex);
		}
	}

	@EventListener
	void onGrammarTopicCreated(GrammarTopicCreated event) {
		try {
			sessions.attachToActive(event.userId(), event.languageCode(), null, event.grammarTopicId());
		}
		catch (RuntimeException ex) {
			log.warn("Couldn't attach grammar topic {} to the active study session", event.grammarTopicId(), ex);
		}
	}

}
