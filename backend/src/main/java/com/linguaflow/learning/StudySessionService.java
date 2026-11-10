package com.linguaflow.learning;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.common.error.NotFoundException;
import com.linguaflow.grammar.GrammarTopicRepository;
import com.linguaflow.user.ActiveLanguageService;
import com.linguaflow.user.UserRepository;
import com.linguaflow.vocabulary.VocabularyItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Study sessions (SES-01): one lesson each, with the words and grammar topics studied in
 * it, scoped to (user, language).
 * <ul>
 * <li>At most one session per language is active (unique index). Activating one ends the
 * others; new words and topics are then attached to it automatically
 * ({@link ActiveSessionAttacher}).</li>
 * <li>Only the user's own material in the session's language can be attached; anything
 * else is a 400 field error. Attaching is idempotent.</li>
 * <li>Deleting a session keeps its words and topics.</li>
 * </ul>
 */
@Service
public class StudySessionService {

	public static final int MAX_TITLE_LENGTH = 200;

	public static final int MAX_NOTES_LENGTH = 5000;

	/** Most words or topics attached in one request. */
	public static final int MAX_ATTACH = 500;

	public record NewSession(String title, LocalDate studiedOn, String notes, boolean active) {
	}

	/** A partial update: null leaves a field unchanged, "" clears the notes. */
	public record SessionChanges(String title, LocalDate studiedOn, String notes, Boolean active) {
	}

	/** A session with how much material it has. */
	public record SessionSummary(StudySession session, long vocabularyCount, long grammarTopicCount) {
	}

	/** What a session covers, for "Current Lesson" practice (PRC-01). */
	public record SessionMaterial(List<UUID> vocabularyItemIds, List<UUID> grammarTopicIds) {
	}

	private final StudySessionRepository sessions;

	private final SessionLinkRepository links;

	private final VocabularyItemRepository vocabulary;

	private final GrammarTopicRepository grammar;

	private final ActiveLanguageService activeLanguages;

	private final UserRepository users;

	private final Clock clock;

	@PersistenceContext
	private EntityManager em;

	StudySessionService(StudySessionRepository sessions, SessionLinkRepository links,
			VocabularyItemRepository vocabulary, GrammarTopicRepository grammar,
			ActiveLanguageService activeLanguages, UserRepository users, Clock clock) {
		this.sessions = sessions;
		this.links = links;
		this.vocabulary = vocabulary;
		this.grammar = grammar;
		this.activeLanguages = activeLanguages;
		this.users = users;
		this.clock = clock;
	}

	/** Creates a session in the active language; {@code studiedOn} defaults to the user's today. */
	@Transactional
	public SessionSummary create(UUID userId, NewSession request) {
		String language = activeLanguages.requireActive(userId);
		StudySession session = new StudySession(userId, language, cleanTitle(request.title()),
				request.studiedOn() == null ? today(userId) : request.studiedOn());
		session.setNotes(cleanNotes(request.notes()));
		sessions.saveAndFlush(session);
		return new SessionSummary(request.active() ? activate(session) : session, 0, 0);
	}

	@Transactional(readOnly = true)
	public SessionSummary get(UUID userId, UUID id) {
		return summarize(List.of(sessions.requireOwned(id, userId))).getFirst();
	}

	/** The active language's sessions, a page at a time. */
	@Transactional(readOnly = true)
	public Page<SessionSummary> list(UUID userId, Pageable pageable) {
		String language = activeLanguages.requireActive(userId);
		Page<StudySession> page = sessions.findAllByUserIdAndLanguageCode(userId, language, pageable);
		List<SessionSummary> summaries = summarize(page.getContent());
		return page.map(session -> summaries.stream()
			.filter(summary -> summary.session().getId().equals(session.getId()))
			.findFirst()
			.orElseThrow());
	}

	/** The active session of the active language, if any. */
	@Transactional(readOnly = true)
	public Optional<SessionSummary> active(UUID userId) {
		String language = activeLanguages.requireActive(userId);
		return sessions.findActive(userId, language).map(session -> summarize(List.of(session)).getFirst());
	}

	@Transactional
	public SessionSummary update(UUID userId, UUID id, SessionChanges changes) {
		StudySession session = sessions.requireOwned(id, userId);
		if (changes.title() != null) {
			session.setTitle(cleanTitle(changes.title()));
		}
		if (changes.studiedOn() != null) {
			session.setStudiedOn(changes.studiedOn());
		}
		if (changes.notes() != null) {
			session.setNotes(cleanNotes(changes.notes()));
		}
		if (Boolean.TRUE.equals(changes.active())) {
			session = activate(session);
		}
		else if (Boolean.FALSE.equals(changes.active())) {
			session.setActive(false);
		}
		sessions.saveAndFlush(session);
		return summarize(List.of(session)).getFirst();
	}

	/** Deletes the session; its words and topics stay. */
	@Transactional
	public void delete(UUID userId, UUID id) {
		if (sessions.deleteByIdAndUserId(id, userId) == 0) {
			throw new NotFoundException("Resource not found");
		}
	}

	/** The IDs of the session's words and topics, in the order they were added. */
	@Transactional(readOnly = true)
	public SessionMaterial material(UUID userId, UUID id) {
		StudySession session = sessions.requireOwned(id, userId);
		return new SessionMaterial(links.vocabularyIds(session.getId()), links.grammarTopicIds(session.getId()));
	}

	/**
	 * Attaches the user's words to the session; ones already attached are skipped.
	 * @throws InvalidFieldException on "vocabularyItemIds" for an unknown word or one in
	 * another language
	 */
	@Transactional
	public SessionSummary attachVocabulary(UUID userId, UUID id, Collection<UUID> itemIds) {
		StudySession session = sessions.requireOwned(id, userId);
		Set<UUID> wanted = checkCount("vocabularyItemIds", itemIds);
		if (vocabulary.findOwnedIds(userId, session.getLanguageCode(), wanted).size() != wanted.size()) {
			throw new InvalidFieldException("vocabularyItemIds", "contains a word that doesn't exist");
		}
		Set<UUID> linked = new HashSet<>(links.vocabularyIds(session.getId()));
		wanted.stream()
			.filter(itemId -> !linked.contains(itemId))
			.forEach(itemId -> em.persist(new SessionVocabulary(session.getId(), itemId, clock.instant())));
		em.flush();
		return summarize(List.of(session)).getFirst();
	}

	/**
	 * Attaches the user's grammar topics to the session; ones already attached are skipped.
	 * @throws InvalidFieldException on "grammarTopicIds" for an unknown topic or one in
	 * another language
	 */
	@Transactional
	public SessionSummary attachGrammar(UUID userId, UUID id, Collection<UUID> topicIds) {
		StudySession session = sessions.requireOwned(id, userId);
		Set<UUID> wanted = checkCount("grammarTopicIds", topicIds);
		if (grammar.findOwnedIds(userId, session.getLanguageCode(), wanted).size() != wanted.size()) {
			throw new InvalidFieldException("grammarTopicIds", "contains a topic that doesn't exist");
		}
		Set<UUID> linked = new HashSet<>(links.grammarTopicIds(session.getId()));
		wanted.stream()
			.filter(topicId -> !linked.contains(topicId))
			.forEach(topicId -> em.persist(new SessionGrammar(session.getId(), topicId, clock.instant())));
		em.flush();
		return summarize(List.of(session)).getFirst();
	}

	/** Detaches a word; 404 if the session isn't the user's or the word isn't attached. */
	@Transactional
	public void detachVocabulary(UUID userId, UUID id, UUID itemId) {
		StudySession session = sessions.requireOwned(id, userId);
		if (links.unlinkVocabulary(session.getId(), itemId) == 0) {
			throw new NotFoundException("Resource not found");
		}
	}

	/** Detaches a topic; 404 if the session isn't the user's or the topic isn't attached. */
	@Transactional
	public void detachGrammar(UUID userId, UUID id, UUID topicId) {
		StudySession session = sessions.requireOwned(id, userId);
		if (links.unlinkGrammar(session.getId(), topicId) == 0) {
			throw new NotFoundException("Resource not found");
		}
	}

	/** Attaches newly created material to the user's active session in that language, if any. */
	@Transactional
	void attachToActive(UUID userId, String languageCode, UUID vocabularyItemId, UUID grammarTopicId) {
		sessions.findActive(userId, languageCode).ifPresent(session -> {
			if (vocabularyItemId != null) {
				em.persist(new SessionVocabulary(session.getId(), vocabularyItemId, clock.instant()));
			}
			if (grammarTopicId != null) {
				em.persist(new SessionGrammar(session.getId(), grammarTopicId, clock.instant()));
			}
		});
	}

	/**
	 * Makes the session the active one. Pending changes are flushed, the bulk update ends
	 * the others and clears the persistence context, so callers must go on with the
	 * returned fresh entity, not the one they passed in.
	 */
	private StudySession activate(StudySession session) {
		sessions.deactivateOthers(session.getUserId(), session.getLanguageCode(), session.getId());
		StudySession fresh = sessions.requireOwned(session.getId(), session.getUserId());
		fresh.setActive(true);
		return sessions.saveAndFlush(fresh);
	}

	private List<SessionSummary> summarize(List<StudySession> page) {
		if (page.isEmpty()) {
			return List.of();
		}
		Set<UUID> ids = page.stream().map(StudySession::getId).collect(Collectors.toSet());
		Map<UUID, Long> words = links.vocabularyCounts(ids)
			.stream()
			.collect(Collectors.toMap(SessionLinkCount::sessionId, SessionLinkCount::count));
		Map<UUID, Long> topics = links.grammarCounts(ids)
			.stream()
			.collect(Collectors.toMap(SessionLinkCount::sessionId, SessionLinkCount::count));
		return page.stream()
			.map(session -> new SessionSummary(session, words.getOrDefault(session.getId(), 0L),
					topics.getOrDefault(session.getId(), 0L)))
			.toList();
	}

	private LocalDate today(UUID userId) {
		ZoneId zone = users.findById(userId).map(user -> {
			try {
				return ZoneId.of(user.getTimezone());
			}
			catch (RuntimeException ex) {
				return (ZoneId) ZoneOffset.UTC;
			}
		}).orElse(ZoneOffset.UTC);
		return LocalDate.now(clock.withZone(zone));
	}

	private static Set<UUID> checkCount(String field, Collection<UUID> ids) {
		Set<UUID> wanted = new LinkedHashSet<>(ids == null ? List.of() : ids);
		if (wanted.isEmpty()) {
			throw new InvalidFieldException(field, "must not be empty");
		}
		if (wanted.size() > MAX_ATTACH) {
			throw new InvalidFieldException(field, "at most " + MAX_ATTACH + " per request");
		}
		return wanted;
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

	private static String cleanNotes(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String notes = raw.stripTrailing();
		if (notes.length() > MAX_NOTES_LENGTH) {
			throw new InvalidFieldException("notes", "must be at most " + MAX_NOTES_LENGTH + " characters");
		}
		return notes;
	}

}
