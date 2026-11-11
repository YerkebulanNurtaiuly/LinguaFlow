package com.linguaflow.flashcard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

import com.linguaflow.flashcard.SrsState.State;
import org.jspecify.annotations.Nullable;

/**
 * Spaced-repetition scheduling (SRS-01): a pure, deterministic SM-2 variant, as Anki
 * popularised it. Given a card's state, a grade, the moment of the review and the
 * learner's time zone, it returns the card's next state. No randomness ("fuzz") and no
 * clock: the same input always gives the same output.
 *
 * <h2>New and learning cards</h2>
 * A NEW card is a LEARNING card at its first step. Learning goes through short steps
 * ({@code learningSteps}, 1 and 10 minutes by default); {@code repetitions} counts the steps
 * passed.
 * <ul>
 * <li>AGAIN: back to the first step.</li>
 * <li>HARD: repeat the current step.</li>
 * <li>GOOD: the next step; after the last one the card graduates to REVIEW with
 * {@code graduatingIntervalDays} (1).</li>
 * <li>EASY: graduates at once with {@code easyIntervalDays} (4).</li>
 * </ul>
 *
 * <h2>Review cards</h2>
 * The interval is in days, and the card is due at the start of that day in the learner's
 * time zone, so "due today" means their today. With interval {@code i} and ease {@code e}:
 * <ul>
 * <li>HARD: {@code i × 1.2}, ease − 0.15.</li>
 * <li>GOOD: {@code i × e}.</li>
 * <li>EASY: {@code i × e × 1.3}, ease + 0.15.</li>
 * <li>Each is at least one day more than the grade below it (HARD at least {@code i + 1}),
 * and at most {@code maxIntervalDays}. {@code repetitions} counts successful reviews in a
 * row.</li>
 * <li>AGAIN is a lapse: {@code lapses} + 1, ease − 0.20, and the card RELEARNS through
 * {@code relearningSteps} (10 minutes) before returning to REVIEW with
 * {@code i × lapseIntervalMultiplier}, at least one day.</li>
 * </ul>
 * Ease never drops below {@code minEase} (1.30) and is kept to two decimals, like
 * {@code srs_state.ease_factor}. The scheduled interval is used, not the days actually
 * elapsed, so reviewing an overdue card earns no bonus.
 *
 * <h2>Relearning cards</h2>
 * Like learning, with {@code relearningSteps}: AGAIN restarts, HARD repeats the step, GOOD
 * moves on and finally returns to REVIEW with the post-lapse interval, and EASY returns at
 * once with one day more.
 */
public final class SrsScheduler {

	/** The tunable numbers; {@link #DEFAULT} follows Anki's defaults. */
	public record Config(List<Duration> learningSteps, List<Duration> relearningSteps, int graduatingIntervalDays,
			int easyIntervalDays, BigDecimal minEase, BigDecimal againEasePenalty, BigDecimal hardEasePenalty,
			BigDecimal easyEaseBonus, double hardMultiplier, double easyBonus, double lapseIntervalMultiplier,
			int maxIntervalDays) {

		public Config {
			learningSteps = List.copyOf(learningSteps);
			relearningSteps = List.copyOf(relearningSteps);
			if (graduatingIntervalDays < 1 || easyIntervalDays < graduatingIntervalDays) {
				throw new IllegalArgumentException("Need 1 <= graduating interval <= easy interval");
			}
			if (maxIntervalDays < easyIntervalDays) {
				throw new IllegalArgumentException("The maximum interval is below the easy interval");
			}
		}

	}

	public static final Config DEFAULT = new Config(List.of(Duration.ofMinutes(1), Duration.ofMinutes(10)),
			List.of(Duration.ofMinutes(10)), 1, 4, new BigDecimal("1.30"), new BigDecimal("0.20"),
			new BigDecimal("0.15"), new BigDecimal("0.15"), 1.2, 1.3, 0.0, 36_500);

	/** A card's scheduling state, as stored in {@code srs_state}. */
	public record Card(State state, BigDecimal ease, int intervalDays, int repetitions, int lapses,
			@Nullable Instant dueAt, @Nullable Instant lastReviewedAt) {

		/** A card that has never been reviewed. */
		public static Card fresh() {
			return new Card(State.NEW, SrsState.INITIAL_EASE, 0, 0, 0, null, null);
		}

	}

	private final Config config;

	public SrsScheduler(Config config) {
		this.config = Objects.requireNonNull(config);
	}

	public SrsScheduler() {
		this(DEFAULT);
	}

	public Config config() {
		return config;
	}

	/**
	 * The card's state after it was graded {@code grade} at {@code now}.
	 * @param zone the learner's time zone; review cards fall due at the start of a local day
	 */
	public Card review(Card card, ReviewGrade grade, Instant now, ZoneId zone) {
		Objects.requireNonNull(grade, "grade");
		return switch (card.state()) {
			case NEW, LEARNING -> learn(card, grade, now, zone);
			case REVIEW -> reviewCard(card, grade, now, zone);
			case RELEARNING -> relearn(card, grade, now, zone);
		};
	}

	/** The learner's calendar day at {@code now}: the "today" of daily limits (SRS-02). */
	public static LocalDate today(Instant now, ZoneId zone) {
		return LocalDate.ofInstant(now, zone);
	}

	// --- New and learning ----------------------------------------------------------------

	private Card learn(Card card, ReviewGrade grade, Instant now, ZoneId zone) {
		List<Duration> steps = config.learningSteps();
		int step = card.state() == State.NEW ? 0 : Math.min(card.repetitions(), Math.max(0, steps.size() - 1));
		return switch (grade) {
			case AGAIN -> steps.isEmpty() ? graduate(card, config.graduatingIntervalDays(), now, zone)
					: atStep(card, State.LEARNING, 0, steps, now);
			case HARD -> steps.isEmpty() ? graduate(card, config.graduatingIntervalDays(), now, zone)
					: atStep(card, State.LEARNING, step, steps, now);
			case GOOD -> step + 1 < steps.size() ? atStep(card, State.LEARNING, step + 1, steps, now)
					: graduate(card, config.graduatingIntervalDays(), now, zone);
			case EASY -> graduate(card, config.easyIntervalDays(), now, zone);
		};
	}

	/** First success: REVIEW, due {@code days} local days from today. */
	private Card graduate(Card card, int days, Instant now, ZoneId zone) {
		return new Card(State.REVIEW, card.ease(), days, 1, card.lapses(), dueOn(now, zone, days), now);
	}

	// --- Review --------------------------------------------------------------------------

	private Card reviewCard(Card card, ReviewGrade grade, Instant now, ZoneId zone) {
		int interval = Math.max(1, card.intervalDays());
		BigDecimal ease = card.ease();
		int hard = cap(Math.max(interval + 1, round(interval * config.hardMultiplier())));
		int good = cap(Math.max(hard + 1, round(interval * ease.doubleValue())));
		int easy = cap(Math.max(good + 1, round(interval * ease.doubleValue() * config.easyBonus())));
		return switch (grade) {
			case AGAIN -> lapse(card, interval, now, zone);
			case HARD -> scheduled(card, ease(ease.subtract(config.hardEasePenalty())), hard, card.repetitions() + 1,
					now, zone);
			case GOOD -> scheduled(card, ease, good, card.repetitions() + 1, now, zone);
			case EASY -> scheduled(card, ease(ease.add(config.easyEaseBonus())), easy, card.repetitions() + 1, now,
					zone);
		};
	}

	private Card lapse(Card card, int interval, Instant now, ZoneId zone) {
		BigDecimal ease = ease(card.ease().subtract(config.againEasePenalty()));
		int afterRelearning = cap(Math.max(1, round(interval * config.lapseIntervalMultiplier())));
		Card lapsed = new Card(State.RELEARNING, ease, afterRelearning, 0, card.lapses() + 1, card.dueAt(),
				card.lastReviewedAt());
		if (config.relearningSteps().isEmpty()) {
			return scheduled(lapsed, ease, afterRelearning, 1, now, zone);
		}
		return atStep(lapsed, State.RELEARNING, 0, config.relearningSteps(), now);
	}

	// --- Relearning ----------------------------------------------------------------------

	private Card relearn(Card card, ReviewGrade grade, Instant now, ZoneId zone) {
		List<Duration> steps = config.relearningSteps();
		int interval = Math.max(1, card.intervalDays());
		int step = Math.min(card.repetitions(), Math.max(0, steps.size() - 1));
		return switch (grade) {
			case AGAIN -> steps.isEmpty() ? scheduled(card, card.ease(), interval, 1, now, zone)
					: atStep(card, State.RELEARNING, 0, steps, now);
			case HARD -> steps.isEmpty() ? scheduled(card, card.ease(), interval, 1, now, zone)
					: atStep(card, State.RELEARNING, step, steps, now);
			case GOOD -> step + 1 < steps.size() ? atStep(card, State.RELEARNING, step + 1, steps, now)
					: scheduled(card, card.ease(), interval, 1, now, zone);
			case EASY -> scheduled(card, card.ease(), cap(interval + 1), 1, now, zone);
		};
	}

	// --- Helpers -------------------------------------------------------------------------

	/** A (re)learning step: due after the step's delay; the interval is kept for later. */
	private static Card atStep(Card card, State state, int step, List<Duration> steps, Instant now) {
		return new Card(state, card.ease(), card.intervalDays(), step, card.lapses(), now.plus(steps.get(step)),
				now);
	}

	private Card scheduled(Card card, BigDecimal ease, int days, int repetitions, Instant now, ZoneId zone) {
		return new Card(State.REVIEW, ease, days, repetitions, card.lapses(), dueOn(now, zone, days), now);
	}

	/** The start of the local day {@code days} after today, in the learner's zone (DST-safe). */
	static Instant dueOn(Instant now, ZoneId zone, int days) {
		return today(now, zone).plusDays(days).atStartOfDay(zone).toInstant();
	}

	private BigDecimal ease(BigDecimal ease) {
		BigDecimal rounded = ease.setScale(2, RoundingMode.HALF_UP);
		return rounded.compareTo(config.minEase()) < 0 ? config.minEase().setScale(2, RoundingMode.HALF_UP) : rounded;
	}

	private int cap(int days) {
		return Math.min(days, config.maxIntervalDays());
	}

	private static int round(double days) {
		return (int) Math.min(Integer.MAX_VALUE, Math.round(days));
	}

}
