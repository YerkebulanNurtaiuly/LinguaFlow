package com.linguaflow.learning.practice;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ConflictException;
import com.linguaflow.common.error.ErrorCode;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.exercise.Exercise;
import com.linguaflow.exercise.ExerciseSetFinishedEvent;
import com.linguaflow.exercise.ExerciseSetService;
import com.linguaflow.exercise.ExerciseSetService.SetWithExercises;
import com.linguaflow.exercise.ExerciseSetStatus;
import com.linguaflow.flashcard.ReviewService;
import com.linguaflow.learning.practice.MaterialSelector.Limits;
import com.linguaflow.user.ActiveLanguageService;
import com.linguaflow.user.LearningSettings;
import com.linguaflow.user.UserLanguageService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Practice sessions (PRC-02). Starting one chooses material by mode
 * ({@link MaterialSelectionService}), shows one card per chosen word and asks the AI for an
 * exercise set on the chosen topics; the exercises join the session, interleaved with the
 * cards, once they're written. Reviews and attempts that name the session mark its items
 * answered ({@link PracticeSessionTracker}), and completing it stores a summary.
 */
@Service
public class PracticeSessionService {

	public static final int MIN_LENGTH = 5;

	public static final int MAX_LENGTH = 200;

	/** Grammar topics one session practises. */
	static final int TOPICS = 3;

	/** Seeds stay JSON-safe: JavaScript numbers hold integers exactly up to 2^53. */
	static final long MAX_SEED = 1L << 53;

	private static final Logger log = LoggerFactory.getLogger(PracticeSessionService.class);

	private final PracticeSessionRepository sessions;

	private final PracticeItems items;

	private final PracticeCandidates candidates;

	private final MaterialSelectionService selection;

	private final ExerciseSetService exerciseSets;

	private final ReviewService reviews;

	private final ActiveLanguageService activeLanguages;

	private final UserLanguageService userLanguages;

	private final Clock clock;

	private final RandomGenerator seeds = new SecureRandom();

	PracticeSessionService(PracticeSessionRepository sessions, PracticeItems items, PracticeCandidates candidates,
			MaterialSelectionService selection, ExerciseSetService exerciseSets, ReviewService reviews,
			ActiveLanguageService activeLanguages, UserLanguageService userLanguages, Clock clock) {
		this.sessions = sessions;
		this.items = items;
		this.candidates = candidates;
		this.selection = selection;
		this.exerciseSets = exerciseSets;
		this.reviews = reviews;
		this.activeLanguages = activeLanguages;
		this.userLanguages = userLanguages;
		this.clock = clock;
	}

	/**
	 * What the learner asks for.
	 * @param studySessionId CURRENT_LESSON only; null means the latest lesson
	 * @param length null means the learner's {@code practiceSessionLength}
	 * @param newRatio MIXED only; null means {@link MaterialSelector#DEFAULT_NEW_RATIO}
	 */
	public record NewPracticeSession(PracticeMode mode, @Nullable UUID studySessionId, @Nullable Integer length,
			boolean includeFlashcards, boolean includeExercises, @Nullable Double newRatio) {
	}

	/**
	 * A session with everything its player needs.
	 * @param cards the flashcard items' cards with grade previews
	 * @param exercises the exercise items' exercises, by ID
	 * @param exerciseSet the set written for the session, if it asked for exercises
	 */
	public record View(PracticeSession session, List<PracticeItem> items, Map<UUID, ReviewService.QueueItem> cards,
			Map<UUID, Exercise> exercises, @Nullable SetWithExercises exerciseSet) {
	}

	/** A session just started, and the job writing its exercises (if any). */
	public record Started(View view, @Nullable UUID aiJobId) {
	}

	/** A session in a list, with how far it got. */
	public record Listed(PracticeSession session, PracticeItems.Progress progress) {
	}

	/**
	 * Starts a session in the active language.
	 * @throws InvalidFieldException for a bad length or ratio, a lesson outside
	 * CURRENT_LESSON, or neither flashcards nor exercises
	 * @throws ApiException 409 {@code NOTHING_TO_PRACTISE} when the material yields no
	 * item, {@code NO_STUDY_SESSION} for CURRENT_LESSON without a lesson
	 */
	@Transactional
	public Started start(UUID userId, NewPracticeSession request) {
		if (!request.includeFlashcards() && !request.includeExercises()) {
			throw new InvalidFieldException("includeFlashcards", "choose flashcards, exercises or both");
		}
		if (request.studySessionId() != null && request.mode() != PracticeMode.CURRENT_LESSON) {
			throw new InvalidFieldException("studySessionId", "is only for CURRENT_LESSON");
		}
		String language = activeLanguages.requireActive(userId);
		LearningSettings settings = userLanguages.settingsFor(userId, language);
		int length = request.length() == null ? settings.practiceSessionLength() : request.length();
		if (length < MIN_LENGTH || length > MAX_LENGTH) {
			throw new InvalidFieldException("length", "must be between " + MIN_LENGTH + " and " + MAX_LENGTH);
		}
		double newRatio = request.newRatio() == null ? MaterialSelector.DEFAULT_NEW_RATIO : request.newRatio();
		if (!(newRatio >= 0 && newRatio <= 1)) {
			throw new InvalidFieldException("newRatio", "must be between 0 and 1");
		}
		// Below 2^53, so JavaScript reads it back exactly
		long seed = seeds.nextLong(MAX_SEED);

		// Twice the length in words, since some have no card to show
		var picked = selection.select(userId, new MaterialSelectionService.Request(request.mode(),
				request.studySessionId(), new Limits(Math.min(2 * length, 2 * MAX_LENGTH), TOPICS, newRatio), seed));
		List<UUID> words = picked.vocabularyItemIds();
		List<UUID> topics = picked.grammarTopicIds();
		List<UUID> cards = request.includeFlashcards()
				? PracticePlan.chooseCards(words, candidates.cards(userId, words, settings.cardDirections()), length)
				: List.of();
		PracticePlan.Counts counts = PracticePlan.counts(length, request.includeFlashcards(), cards.size(),
				request.includeExercises(), !topics.isEmpty());
		if (counts.total() == 0) {
			throw new ApiException(ErrorCode.NOTHING_TO_PRACTISE, nothingToPractise(request));
		}

		Instant now = now();
		PracticeSession session = sessions.saveAndFlush(new PracticeSession(userId, language, request.mode(),
				picked.studySessionId(),
				new PracticeConfig(length, request.includeFlashcards(), request.includeExercises(), newRatio, seed),
				now));
		List<PracticeItem> cardItems = new ArrayList<>();
		for (UUID card : cards.subList(0, counts.flashcards())) {
			cardItems.add(PracticeItem.flashcard(cardItems.size(), card));
		}
		items.replace(session.getId(), cardItems);
		UUID jobId = null;
		if (counts.exercises() > 0) {
			jobId = exerciseSets
				.createForPractice(userId, language, session.getId(), topics, words, counts.exercises())
				.aiJobId();
		}
		return new Started(view(session), jobId);
	}

	private static String nothingToPractise(NewPracticeSession request) {
		if (request.includeExercises() && !request.includeFlashcards()) {
			return "There's no grammar topic to practise with exercises yet; add a topic first.";
		}
		return "There's nothing to practise yet: add words (their flashcards need a translation) or grammar topics.";
	}

	@Transactional(readOnly = true)
	public View get(UUID userId, UUID id) {
		return view(sessions.requireOwned(id, userId));
	}

	/** The active language's sessions with their progress. */
	@Transactional(readOnly = true)
	public Page<Listed> list(UUID userId, Pageable pageable) {
		String language = activeLanguages.requireActive(userId);
		Page<PracticeSession> page = sessions.findAllByUserIdAndLanguageCode(userId, language, pageable);
		Map<UUID, PracticeItems.Progress> progress = items.progress(page.map(PracticeSession::getId).getContent());
		return page.map(session -> new Listed(session,
				progress.getOrDefault(session.getId(), new PracticeItems.Progress(0, 0))));
	}

	/**
	 * Finishes a session and stores its summary; completing it again returns it as it is.
	 * @throws ConflictException for an abandoned session
	 */
	@Transactional
	public View complete(UUID userId, UUID id) {
		PracticeSession session = sessions.requireOwned(id, userId);
		if (session.getStatus() == PracticeSessionStatus.ABANDONED) {
			throw new ConflictException("This practice session was abandoned.");
		}
		if (session.inProgress()) {
			session.complete(PracticeSummary.of(items.list(id), items.reviews(userId, id), items.attempts(userId, id)),
					now());
			session = sessions.saveAndFlush(session);
		}
		return view(session);
	}

	private View view(PracticeSession session) {
		UUID userId = session.getUserId();
		List<PracticeItem> list = items.list(session.getId());
		List<UUID> cardIds = list.stream()
			.filter(item -> item.type() == PracticeItemType.FLASHCARD)
			.map(PracticeItem::refId)
			.toList();
		Map<UUID, ReviewService.QueueItem> cards = new LinkedHashMap<>();
		reviews.items(userId, cardIds).forEach(card -> cards.put(card.view().card().getId(), card));
		List<UUID> exerciseIds = list.stream()
			.filter(item -> item.type() == PracticeItemType.EXERCISE)
			.map(PracticeItem::refId)
			.toList();
		Map<UUID, Exercise> exercises = new LinkedHashMap<>();
		exerciseSets.exercises(userId, exerciseIds).forEach(exercise -> exercises.put(exercise.getId(), exercise));
		Optional<SetWithExercises> set = exerciseSets.forPracticeSession(userId, session.getId());
		return new View(session, list, cards, exercises, set.orElse(null));
	}

	/**
	 * What each mode would practise in the active language, for the practice hub
	 * (FE-PRC-01).
	 * @param currentLesson the latest lesson, null without any
	 * @param words enriched words, which have cards to show
	 * @param freshWords of those, added in the last week or never practised (MIXED's new share)
	 * @param inProgress the latest unfinished session, to continue
	 */
	public record Overview(@Nullable Lesson currentLesson, int words, int freshWords, int topics,
			int practiceSessionLength, @Nullable Listed inProgress) {
	}

	/** A lesson with how much material it has. */
	public record Lesson(UUID id, String title, LocalDate studiedOn, int words, int topics) {
	}

	@Transactional(readOnly = true)
	public Overview overview(UUID userId) {
		String language = activeLanguages.requireActive(userId);
		Instant now = now();
		var words = candidates.words(userId, language, null);
		int fresh = (int) words.stream().filter(word -> PracticeWeights.word(word, now).fresh()).count();
		int topics = candidates.topics(userId, language, null).size();
		Lesson lesson = candidates.latestLesson(userId, language)
			.map(found -> new Lesson(found.id(), found.title(), found.studiedOn(),
					candidates.words(userId, language, found.id()).size(),
					candidates.topics(userId, language, found.id()).size()))
			.orElse(null);
		Listed inProgress = sessions.findFirstByUserIdAndLanguageCodeAndStatusOrderByStartedAtDesc(userId, language,
				PracticeSessionStatus.IN_PROGRESS)
			.map(session -> new Listed(session, items.progress(List.of(session.getId()))
				.getOrDefault(session.getId(), new PracticeItems.Progress(0, 0))))
			.orElse(null);
		return new Overview(lesson, words.size(), fresh, topics,
				userLanguages.settingsFor(userId, language).practiceSessionLength(), inProgress);
	}

	/**
	 * "Practise mistakes again": a new session with the cards and exercises a completed
	 * session got wrong (its summary's missed items), in the same mode. Cards come first,
	 * interleaved with the exercises; deleted and reported ones are left out.
	 * @throws ConflictException unless the session is completed with something missed
	 */
	@Transactional
	public Started retry(UUID userId, UUID id) {
		PracticeSession original = sessions.requireOwned(id, userId);
		PracticeSummary summary = original.getSummary();
		if (original.getStatus() != PracticeSessionStatus.COMPLETED || summary == null) {
			throw new ConflictException("Complete the session first.");
		}
		List<UUID> cards = reviews.items(userId, summary.missedFlashcardIds())
			.stream()
			.map(card -> card.view().card().getId())
			.toList();
		Map<UUID, Exercise> found = new LinkedHashMap<>();
		exerciseSets.exercises(userId, summary.missedExerciseIds())
			.stream()
			.filter(exercise -> !exercise.isReported())
			.forEach(exercise -> found.put(exercise.getId(), exercise));
		List<UUID> exercises = summary.missedExerciseIds().stream().filter(found::containsKey).toList();
		if (cards.isEmpty() && exercises.isEmpty()) {
			throw new ConflictException("There are no mistakes left to practise from this session.");
		}
		PracticeConfig config = original.getConfig();
		PracticeSession session = sessions.saveAndFlush(new PracticeSession(userId, original.getLanguageCode(),
				original.getMode(), original.getStudySessionId(),
				new PracticeConfig(cards.size() + exercises.size(), !cards.isEmpty(), !exercises.isEmpty(),
						config.newRatio(), config.seed()),
				now()));
		List<PracticeItem> cardItems = cards.stream().map(card -> PracticeItem.flashcard(0, card)).toList();
		List<PracticeItem> exerciseItems = exercises.stream()
			.map(exercise -> PracticeItem.exercise(0, exercise))
			.toList();
		items.replace(session.getId(), PracticePlan.interleave(cardItems, exerciseItems, 0));
		return new Started(view(session), null);
	}

	/**
	 * Adds a session's exercises once they're written, in the transaction that marks the
	 * set READY. Reported exercises are left out; a finished session is left alone.
	 */
	@EventListener
	@Transactional
	public void onExerciseSetFinished(ExerciseSetFinishedEvent event) {
		if (event.practiceSessionId() == null || event.status() != ExerciseSetStatus.READY) {
			return;
		}
		Optional<PracticeSession> session = sessions.findByIdAndUserId(event.practiceSessionId(), event.userId());
		if (session.isEmpty() || !session.get().inProgress()) {
			return;
		}
		List<UUID> exerciseIds = exerciseSets.get(event.userId(), event.exerciseSetId())
			.exercises()
			.stream()
			.filter(exercise -> !exercise.isReported())
			.map(Exercise::getId)
			.toList();
		UUID sessionId = session.get().getId();
		items.replace(sessionId, PracticePlan.withExercises(items.list(sessionId), exerciseIds));
		log.debug("Added {} exercises to practice session {}", exerciseIds.size(), sessionId);
	}

	/** Now, to the microsecond PostgreSQL stores. */
	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

}
