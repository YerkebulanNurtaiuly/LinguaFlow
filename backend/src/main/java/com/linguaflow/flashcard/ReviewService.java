package com.linguaflow.flashcard;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
	 * Review statistics for the active language (SRS-03), on the learner's calendar.
	 * @param dueToday cards due before today ends, overdue and later learning steps included
	 * @param newAvailable new cards still available today, within the daily limit
	 * @param currentStreak days in a row with a review, ending today, or yesterday while
	 * today has no review yet
	 * @param forecast cards falling due on each of the next 7 days, today (with overdue
	 * cards) first
	 */
	public record Stats(LocalDate today, long dueToday, long newAvailable, long reviewedToday, int dailyReviewGoal,
			int currentStreak, int longestStreak, Retention retention, List<ForecastDay> forecast) {
	}

	/**
	 * How often review cards were remembered (not AGAIN) over the last {@code days} days,
	 * learning and relearning steps left out; {@code rate} is null without any review.
	 */
	public record Retention(int days, long reviews, long remembered, @Nullable Double rate) {
	}

	public record ForecastDay(LocalDate date, long due) {
	}

	/** Days of reviews retention is measured over. */
	public static final int RETENTION_DAYS = 30;

	/** Days the forecast covers, today included. */
	public static final int FORECAST_DAYS = 7;

	@Transactional(readOnly = true)
	public Stats stats(UUID userId) {
		String language = activeLanguages.requireActive(userId);
		LearningSettings settings = userLanguages.settingsFor(userId, language);
		ZoneId zone = zoneOf(userId);
		LocalDate today = SrsScheduler.today(now(), zone);
		Instant startOfToday = today.atStartOfDay(zone).toInstant();
		var directions = settings.cardDirections();

		long[] perDay = new long[FORECAST_DAYS];
		Instant forecastEnd = today.plusDays(FORECAST_DAYS).atStartOfDay(zone).toInstant();
		for (Instant due : cards.findDueDatesBefore(userId, language, directions, forecastEnd)) {
			LocalDate day = LocalDate.ofInstant(due, zone);
			perDay[day.isBefore(today) ? 0 : (int) (day.toEpochDay() - today.toEpochDay())]++;
		}
		List<ForecastDay> forecast = new ArrayList<>();
		for (int i = 0; i < FORECAST_DAYS; i++) {
			forecast.add(new ForecastDay(today.plusDays(i), perDay[i]));
		}

		long introduced = reviews.countIntroducedSince(userId, language, startOfToday);
		long newLeft = Math.max(0, settings.newCardsPerDay() - introduced);
		long newAvailable = newLeft == 0 ? 0
				: Math.min(newLeft, cards.countNew(userId, language, directions, startOfToday));

		List<LocalDate> days = reviews.findReviewDays(userId, language, postgresZone(zone))
			.stream()
			.map(LocalDate::parse)
			.toList();
		Instant retentionStart = today.minusDays(RETENTION_DAYS - 1).atStartOfDay(zone).toInstant();
		long reviewed = reviews.countInStateSince(userId, language, SrsState.State.REVIEW, retentionStart,
				EnumSet.allOf(ReviewGrade.class));
		long remembered = reviews.countInStateSince(userId, language, SrsState.State.REVIEW, retentionStart,
				EnumSet.complementOf(EnumSet.of(ReviewGrade.AGAIN)));
		Double rate = reviewed == 0 ? null : Math.round(remembered * 1000.0 / reviewed) / 1000.0;

		return new Stats(today, perDay[0], newAvailable, reviews.countReviewedSince(userId, language, startOfToday),
				settings.dailyReviewGoal(), currentStreak(days, today), longestStreak(days),
				new Retention(RETENTION_DAYS, reviewed, remembered, rate), forecast);
	}

	/** Days in a row with a review up to today, or up to yesterday while today has none yet. */
	static int currentStreak(Collection<LocalDate> reviewDays, LocalDate today) {
		Set<LocalDate> days = new HashSet<>(reviewDays);
		LocalDate day = days.contains(today) ? today : today.minusDays(1);
		int streak = 0;
		while (days.contains(day)) {
			streak++;
			day = day.minusDays(1);
		}
		return streak;
	}

	/** The longest run of consecutive review days. */
	static int longestStreak(Collection<LocalDate> reviewDays) {
		int longest = 0;
		int run = 0;
		LocalDate previous = null;
		for (LocalDate day : new TreeSet<>(reviewDays)) {
			run = previous != null && previous.plusDays(1).equals(day) ? run + 1 : 1;
			longest = Math.max(longest, run);
			previous = day;
		}
		return longest;
	}

	/**
	 * The zone's name for PostgreSQL. Profiles hold IANA names or "UTC" (USR-01), which it
	 * knows; the only offset {@link #zoneOf} returns is the UTC fallback, Java's "Z".
	 */
	private static String postgresZone(ZoneId zone) {
		return zone instanceof ZoneOffset ? "UTC" : zone.getId();
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
