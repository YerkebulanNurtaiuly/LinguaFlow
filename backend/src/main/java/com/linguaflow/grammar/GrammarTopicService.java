package com.linguaflow.grammar;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.ai.job.AiJobQueue;
import com.linguaflow.ai.job.AiJobType;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.common.error.NotFoundException;
import com.linguaflow.user.ActiveLanguageService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The learner's grammar topics (GRM-01), scoped to the caller; new topics go into the
 * active language.
 * <ul>
 * <li>Titles are unique per language after {@link TitleNormalizer normalization}; a
 * duplicate is 409 with the existing ID, also when two requests race.</li>
 * <li>Status (GRM-02): a topic starts as NEW, LEARNING or LEARNED, never MASTERED. Later
 * the learner may choose LEARNING or LEARNED, and MASTERED only with explicit
 * confirmation; otherwise mastery is granted by progress rules (PRG-02). The rules live
 * in {@link GrammarTopic}. Every change is recorded in the topic's history.</li>
 * </ul>
 */
@Service
public class GrammarTopicService {

	public static final int MAX_TITLE_LENGTH = 200;

	public static final int MAX_DESCRIPTION_LENGTH = 2000;

	public static final int MAX_NOTES_LENGTH = 20_000;

	/**
	 * A new topic; only the title is required, the status defaults to NEW.
	 * {@code catalogTopicId} links it to the catalog entry it was picked from (GRM-03).
	 */
	public record NewGrammarTopic(String title, String description, String personalNotes, CefrLevel cefrLevel,
			KnowledgeStatus status, UUID catalogTopicId) {

		public NewGrammarTopic(String title, String description, String personalNotes, CefrLevel cefrLevel,
				KnowledgeStatus status) {
			this(title, description, personalNotes, cefrLevel, status, null);
		}

		public static NewGrammarTopic of(String title) {
			return new NewGrammarTopic(title, null, null, null, null);
		}

	}

	/**
	 * A partial update: null leaves a field unchanged, "" clears description or notes.
	 * {@code confirmMastered} must be true to set MASTERED by hand.
	 */
	public record GrammarTopicChanges(String title, String description, String personalNotes, CefrLevel cefrLevel,
			KnowledgeStatus status, boolean confirmMastered) {

		public GrammarTopicChanges(String title, String description, String personalNotes, CefrLevel cefrLevel,
				KnowledgeStatus status) {
			this(title, description, personalNotes, cefrLevel, status, false);
		}

	}

	private final GrammarTopicRepository topics;

	private final ActiveLanguageService activeLanguages;

	private final TransactionTemplate transactions;

	private final Clock clock;

	private final ApplicationEventPublisher events;

	private final AiJobQueue aiJobs;

	GrammarTopicService(GrammarTopicRepository topics, ActiveLanguageService activeLanguages,
			PlatformTransactionManager transactionManager, Clock clock, ApplicationEventPublisher events,
			AiJobQueue aiJobs) {
		this.topics = topics;
		this.aiJobs = aiJobs;
		this.events = events;
		this.activeLanguages = activeLanguages;
		this.transactions = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/** The AI job writing a topic's explanation (GRM-04); {@code alreadyRunning} when it was queued before. */
	public record ExplanationRequest(UUID aiJobId, boolean alreadyRunning) {
	}

	/**
	 * Asks the AI to explain the topic (GRM-04), or to write a new explanation. While one is
	 * still queued or running, that job is returned instead of queueing the same work twice.
	 * Follow it with {@code GET /api/ai/jobs/{id}}; the explanation then appears on the topic.
	 */
	public ExplanationRequest requestExplanation(UUID userId, UUID id) {
		topics.requireOwned(id, userId);
		return aiJobs
			.findActive(AiJobType.GRAMMAR_EXPLANATION, userId, GrammarExplanationHandler.TOPIC_ID, id.toString())
			.map(job -> new ExplanationRequest(job.getId(), true))
			.orElseGet(() -> new ExplanationRequest(aiJobs
				.enqueue(AiJobType.GRAMMAR_EXPLANATION, userId, Map.of(GrammarExplanationHandler.TOPIC_ID, id.toString()))
				.getId(), false));
	}

	/**
	 * The user's topics among {@code ids} in {@code languageCode}; others' topics, other
	 * languages and unknown IDs are left out. For other modules, e.g. exercise generation
	 * (EX-02).
	 */
	@Transactional(readOnly = true)
	public List<GrammarTopicSummary> summaries(UUID userId, String languageCode, Collection<UUID> ids) {
		return ids.isEmpty() ? List.of() : topics.findSummaries(userId, languageCode, ids);
	}

	/**
	 * Adds a topic to the active language.
	 * @throws DuplicateGrammarTopicException if the user has a topic with that title
	 */
	public GrammarTopic create(UUID userId, NewGrammarTopic request) {
		String language = activeLanguages.requireActive(userId);
		String title = cleanTitle(request.title());
		String normalized = TitleNormalizer.normalize(language, title);
		KnowledgeStatus status = request.status() == null ? KnowledgeStatus.NEW : request.status();
		checkStatus(status);
		rejectDuplicate(userId, language, normalized, title, null);

		GrammarTopic topic = new GrammarTopic(userId, language, title, normalized, status, clock.instant());
		topic.setDescription(clean(request.description(), "description", MAX_DESCRIPTION_LENGTH));
		topic.setPersonalNotes(cleanNotes(request.personalNotes()));
		topic.setCefrLevel(request.cefrLevel());
		topic.setCatalogTopicId(request.catalogTopicId());
		GrammarTopic created = saveRejectingDuplicates(() -> topics.saveAndFlush(topic), userId, () -> language, title);
		// After the commit, so listeners see the topic (e.g. the active study session, SES-01)
		events.publishEvent(new GrammarTopicCreated(userId, language, created.getId()));
		return created;
	}

	/** The user's topic, or 404 if it doesn't exist or belongs to someone else. */
	@Transactional(readOnly = true)
	public GrammarTopic get(UUID userId, UUID id) {
		return topics.requireOwned(id, userId);
	}

	/** A page of the user's topics in the active language; sort properties are entity attributes. */
	@Transactional(readOnly = true)
	public Page<GrammarTopic> list(UUID userId, GrammarTopicFilter filter, Pageable pageable) {
		return topics.search(userId, activeLanguages.requireActive(userId), filter, pageable);
	}

	/** Applies a partial update; a new title is checked for duplicates again. */
	public GrammarTopic update(UUID userId, UUID id, GrammarTopicChanges changes) {
		String title = changes.title() == null ? null : cleanTitle(changes.title());
		String[] language = new String[1];
		return saveRejectingDuplicates(() -> transactions.execute(tx -> {
			GrammarTopic topic = topics.requireOwned(id, userId);
			language[0] = topic.getLanguageCode();
			if (title != null) {
				String normalized = TitleNormalizer.normalize(topic.getLanguageCode(), title);
				rejectDuplicate(userId, topic.getLanguageCode(), normalized, title, topic.getId());
				topic.rename(title, normalized);
			}
			if (changes.description() != null) {
				topic.setDescription(clean(changes.description(), "description", MAX_DESCRIPTION_LENGTH));
			}
			if (changes.personalNotes() != null) {
				topic.setPersonalNotes(cleanNotes(changes.personalNotes()));
			}
			if (changes.cefrLevel() != null) {
				topic.setCefrLevel(changes.cefrLevel());
			}
			if (changes.status() != null) {
				try {
					topic.changeStatusByUser(changes.status(), changes.confirmMastered(), clock.instant());
				}
				catch (IllegalArgumentException ex) {
					throw new InvalidFieldException("status", ex.getMessage());
				}
			}
			return topics.saveAndFlush(topic);
		}), userId, () -> language[0], title);
	}

	/** The topic's status changes, oldest first; 404 if it isn't the user's. */
	@Transactional(readOnly = true)
	public List<GrammarTopicStatusChange> history(UUID userId, UUID id) {
		return List.copyOf(topics.requireOwned(id, userId).getStatusHistory());
	}

	/** Deletes the topic with its history; exercises about it stay. */
	@Transactional
	public void delete(UUID userId, UUID id) {
		if (topics.deleteByIdAndUserId(id, userId) == 0) {
			throw new NotFoundException("Resource not found");
		}
	}

	private void rejectDuplicate(UUID userId, String language, String normalized, String title, UUID self) {
		topics.findDuplicateId(userId, language, normalized)
			.filter(existing -> !existing.equals(self))
			.ifPresent(existing -> {
				throw new DuplicateGrammarTopicException(title, existing);
			});
	}

	/**
	 * A concurrent request may take the title between the check and the flush; the unique
	 * constraint then reports it like any duplicate, looked up after the failed transaction.
	 */
	private GrammarTopic saveRejectingDuplicates(Supplier<GrammarTopic> save, UUID userId,
			Supplier<String> language, String title) {
		try {
			return save.get();
		}
		catch (DataIntegrityViolationException ex) {
			if (title == null || language.get() == null || ex.getMessage() == null
					|| !ex.getMessage().contains(GrammarTopicRepository.TITLE_UNIQUE_CONSTRAINT)) {
				throw ex;
			}
			// The language is read only now: an update learns it inside its transaction
			String key = TitleNormalizer.normalize(language.get(), title);
			UUID existing = topics.findDuplicateId(userId, language.get(), key).orElseThrow(() -> ex);
			throw new DuplicateGrammarTopicException(title, existing);
		}
	}

	private static String cleanTitle(String raw) {
		String title = raw == null ? "" : raw.replaceAll("[\\s\\p{Z}]+", " ").strip();
		if (title.isEmpty()) {
			throw new InvalidFieldException("title", "must not be blank");
		}
		if (title.length() > MAX_TITLE_LENGTH) {
			throw new InvalidFieldException("title", "must be at most " + MAX_TITLE_LENGTH + " characters");
		}
		return title;
	}

	private static String clean(String raw, String field, int max) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String value = raw.strip();
		if (value.length() > max) {
			throw new InvalidFieldException(field, "must be at most " + max + " characters");
		}
		return value;
	}

	/** Notes are Markdown, so only trailing whitespace goes; leading indentation matters. */
	private static String cleanNotes(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String value = raw.stripTrailing();
		if (value.length() > MAX_NOTES_LENGTH) {
			throw new InvalidFieldException("personalNotes", "must be at most " + MAX_NOTES_LENGTH + " characters");
		}
		return value;
	}

	private static void checkStatus(KnowledgeStatus status) {
		if (status == KnowledgeStatus.MASTERED) {
			throw new InvalidFieldException("status", "can't be set to MASTERED; mastery is earned through practice");
		}
	}

}
