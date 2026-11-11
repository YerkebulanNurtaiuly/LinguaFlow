package com.linguaflow.flashcard;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.linguaflow.flashcard.FlashcardService.CardView;
import com.linguaflow.user.ActiveLanguageService;
import com.linguaflow.user.LearningSettings;
import com.linguaflow.user.UserLanguageService;
import com.linguaflow.user.UserRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Daily flashcard review (SRS-02): the queue of cards to review now, and recording a
 * grade. Scheduling itself is {@link SrsScheduler}'s; "today" is the learner's, from
 * their time zone.
 */
@Service
public class ReviewService {

	/** The most cards one queue request returns. */
	public static final int MAX_LIMIT = 200;

	/** A card to review, with where each grade would send it. */
	public record QueueItem(CardView view, Map<ReviewGrade, SrsScheduler.Card> previews) {
	}

	/**
	 * The review queue.
	 * @param dueCount every card due now, including those beyond {@code limit}
	 * @param newCount new cards still available today, within the daily limit
	 * @param nextLearningDueAt when a learning step comes due later today, for "come back at…"
	 */
	public record Queue(List<QueueItem> cards, long dueCount, long newCount, LocalDate today, long reviewedToday,
			int dailyReviewGoal, long newCardsToday, int newCardsPerDay, @Nullable Instant nextLearningDueAt) {
	}

	public record Submission(UUID flashcardId, ReviewGrade grade, UUID reviewToken, @Nullable Integer durationMs) {
	}

	/** @param replayed true when this token was already recorded and nothing changed */
	public record Result(FlashcardReview review, CardView card, boolean replayed) {
	}

	private final FlashcardRepository cards;

	private final SrsStateRepository srsStates;

	private final FlashcardReviewRepository reviews;

	private final FlashcardService flashcards;

	private final ActiveLanguageService activeLanguages;

	private final UserLanguageService userLanguages;

	private final UserRepository users;

	private final TransactionTemplate transactions;

	private final Clock clock;

	private final SrsScheduler scheduler = new SrsScheduler();

	ReviewService(FlashcardRepository cards, SrsStateRepository srsStates, FlashcardReviewRepository reviews,
			FlashcardService flashcards, ActiveLanguageService activeLanguages, UserLanguageService userLanguages,
			UserRepository users, PlatformTransactionManager transactionManager, Clock clock) {
		this.cards = cards;
		this.srsStates = srsStates;
		this.reviews = reviews;
		this.flashcards = flashcards;
		this.activeLanguages = activeLanguages;
		this.userLanguages = userLanguages;
		this.users = users;
		this.transactions = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/**
	 * Up to {@code limit} cards of the active language to review now: overdue cards first
	 * (oldest first), then the rest due today (learning steps, then the weakest), then new
	 * cards up to what's left of {@code newCardsPerDay}. Suspended cards, directions the
	 * learner doesn't review and cards without a translation are left out.
	 */
	@Transactional(readOnly = true)
	public Queue queue(UUID userId, int limit) {
		String language = activeLanguages.requireActive(userId);
		LearningSettings settings = userLanguages.settingsFor(userId, language);
		ZoneId zone = zoneOf(userId);
		Instant now = now();
		LocalDate today = SrsScheduler.today(now, zone);
		Instant startOfToday = today.atStartOfDay(zone).toInstant();
		Instant tomorrow = today.plusDays(1).atStartOfDay(zone).toInstant();
		var directions = settings.cardDirections();

		List<Flashcard> queue = new ArrayList<>(
				cards.findDue(userId, language, directions, now, startOfToday, tomorrow, Limit.of(limit)));
		long dueCount = cards.countDue(userId, language, directions, now, tomorrow);
		long introduced = reviews.countIntroducedSince(userId, language, startOfToday);
		long newLeft = Math.max(0, settings.newCardsPerDay() - introduced);
		long newCount = newLeft == 0 ? 0
				: Math.min(newLeft, cards.countNew(userId, language, directions, startOfToday));
		int room = (int) Math.min(limit - queue.size(), newCount);
		if (room > 0) {
			queue.addAll(cards.findNew(userId, language, directions, startOfToday, Limit.of(room)));
		}

		List<QueueItem> items = flashcards.views(userId, queue)
			.stream()
			.map(view -> new QueueItem(view, previews(view.srs().toCard(), now, zone)))
			.toList();
		return new Queue(items, dueCount, newCount, today, reviews.countReviewedSince(userId, language, startOfToday),
				settings.dailyReviewGoal(), introduced, settings.newCardsPerDay(),
				cards.findNextLearningDue(userId, language, directions, now, tomorrow));
	}

	/**
	 * Records a grade: reschedules the card and appends to the review log. Idempotent per
	 * (card, review token): sending the same token again changes nothing and returns the
	 * stored review, also when two copies race.
	 */
	public Result submit(UUID userId, Submission submission) {
		try {
			return transactions.execute(status -> record(userId, submission));
		}
		catch (DataIntegrityViolationException | OptimisticLockingFailureException ex) {
			// A concurrent copy of this submission won the race; anything else is a real conflict
			return transactions.execute(status -> replay(userId, submission)).orElseThrow(() -> ex);
		}
	}

	private Result record(UUID userId, Submission submission) {
		Flashcard card = cards.requireOwned(submission.flashcardId(), userId);
		Optional<Result> earlier = replay(userId, submission);
		if (earlier.isPresent()) {
			return earlier.get();
		}
		SrsState srs = srsStates.requireOwned(card.getId(), userId);
		Instant now = now();
		SrsScheduler.Card before = srs.toCard();
		SrsScheduler.Card after = scheduler.review(before, submission.grade(), now, zoneOf(userId));
		srs.apply(after);
		srsStates.saveAndFlush(srs);
		FlashcardReview review = reviews.saveAndFlush(new FlashcardReview(card, submission.reviewToken(),
				submission.grade(), now, submission.durationMs(), before, after));
		return new Result(review, flashcards.views(userId, List.of(card)).getFirst(), false);
	}

	private Optional<Result> replay(UUID userId, Submission submission) {
		return reviews
			.findByUserIdAndFlashcardIdAndReviewToken(userId, submission.flashcardId(), submission.reviewToken())
			.map(review -> new Result(review, flashcards.get(userId, submission.flashcardId()), true));
	}

	private Map<ReviewGrade, SrsScheduler.Card> previews(SrsScheduler.Card card, Instant now, ZoneId zone) {
		Map<ReviewGrade, SrsScheduler.Card> previews = new EnumMap<>(ReviewGrade.class);
		for (ReviewGrade grade : ReviewGrade.values()) {
			previews.put(grade, scheduler.review(card, grade, now, zone));
		}
		return previews;
	}

	/** Now, to the microsecond PostgreSQL stores, so a replayed review reads the same. */
	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	/** The learner's time zone; UTC if it can't be read. */
	private ZoneId zoneOf(UUID userId) {
		return users.findById(userId).map(user -> {
			try {
				return ZoneId.of(user.getTimezone());
			}
			catch (RuntimeException ex) {
				return (ZoneId) ZoneOffset.UTC;
			}
		}).orElse(ZoneOffset.UTC);
	}

}
