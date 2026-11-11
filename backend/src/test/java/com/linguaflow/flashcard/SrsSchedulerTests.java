package com.linguaflow.flashcard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.linguaflow.flashcard.SrsScheduler.Card;
import com.linguaflow.flashcard.SrsState.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** SM-2 scheduling (SRS-01): every transition, table-driven, with the default settings. */
class SrsSchedulerTests {

	private static final SrsScheduler SCHEDULER = new SrsScheduler();

	/** 15:00 in Almaty (UTC+5, no daylight saving): local "today" is 2026-11-11. */
	private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

	private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");

	private static Card card(State state, String ease, int interval, int repetitions, int lapses) {
		Instant due = state == State.NEW ? null : NOW.minus(Duration.ofHours(1));
		return new Card(state, new BigDecimal(ease), interval, repetitions, lapses, due,
				state == State.NEW ? null : NOW.minus(Duration.ofDays(interval)));
	}

	/**
	 * {@code due} is either "+&lt;minutes&gt;m" after the review, or "d&lt;days&gt;": the
	 * start of that many local days after today.
	 */
	private static Instant due(String due) {
		if (due.startsWith("+")) {
			return NOW.plus(Duration.ofMinutes(Long.parseLong(due.substring(1, due.length() - 1))));
		}
		int days = Integer.parseInt(due.substring(1));
		return LocalDate.of(2026, 11, 11).plusDays(days).atStartOfDay(ALMATY).toInstant();
	}

	@ParameterizedTest(name = "{0} ease {1} ivl {2} reps {3} lapses {4} + {5} → {6} ease {7} ivl {8} reps {9} lapses {10} due {11}")
	@CsvSource(delimiter = '|', textBlock = """
			# New and learning: steps of 1 and 10 minutes, then graduate
			NEW        | 2.50 |     0 | 0 | 0 | AGAIN | LEARNING   | 2.50 |     0 | 0 | 0 | +1m
			NEW        | 2.50 |     0 | 0 | 0 | HARD  | LEARNING   | 2.50 |     0 | 0 | 0 | +1m
			NEW        | 2.50 |     0 | 0 | 0 | GOOD  | LEARNING   | 2.50 |     0 | 1 | 0 | +10m
			NEW        | 2.50 |     0 | 0 | 0 | EASY  | REVIEW     | 2.50 |     4 | 1 | 0 | d4
			LEARNING   | 2.50 |     0 | 1 | 0 | AGAIN | LEARNING   | 2.50 |     0 | 0 | 0 | +1m
			LEARNING   | 2.50 |     0 | 1 | 0 | HARD  | LEARNING   | 2.50 |     0 | 1 | 0 | +10m
			LEARNING   | 2.50 |     0 | 1 | 0 | GOOD  | REVIEW     | 2.50 |     1 | 1 | 0 | d1
			LEARNING   | 2.50 |     0 | 1 | 0 | EASY  | REVIEW     | 2.50 |     4 | 1 | 0 | d4
			LEARNING   | 2.50 |     0 | 0 | 0 | GOOD  | LEARNING   | 2.50 |     0 | 1 | 0 | +10m
			# Review, interval 10: HARD ×1.2, GOOD ×ease, EASY ×ease×1.3; AGAIN lapses
			REVIEW     | 2.50 |    10 | 3 | 0 | HARD  | REVIEW     | 2.35 |    12 | 4 | 0 | d12
			REVIEW     | 2.50 |    10 | 3 | 0 | GOOD  | REVIEW     | 2.50 |    25 | 4 | 0 | d25
			REVIEW     | 2.50 |    10 | 3 | 0 | EASY  | REVIEW     | 2.65 |    33 | 4 | 0 | d33
			REVIEW     | 2.50 |    10 | 3 | 0 | AGAIN | RELEARNING | 2.30 |     1 | 0 | 1 | +10m
			# Small intervals still grow, and each grade beats the one below by a day
			REVIEW     | 2.50 |     1 | 1 | 0 | HARD  | REVIEW     | 2.35 |     2 | 2 | 0 | d2
			REVIEW     | 2.50 |     1 | 1 | 0 | GOOD  | REVIEW     | 2.50 |     3 | 2 | 0 | d3
			REVIEW     | 2.50 |     1 | 1 | 0 | EASY  | REVIEW     | 2.65 |     4 | 2 | 0 | d4
			# Ease never drops below 1.30
			REVIEW     | 1.30 |    10 | 2 | 3 | AGAIN | RELEARNING | 1.30 |     1 | 0 | 4 | +10m
			REVIEW     | 1.30 |    10 | 2 | 3 | HARD  | REVIEW     | 1.30 |    12 | 3 | 3 | d12
			REVIEW     | 1.30 |    10 | 2 | 3 | GOOD  | REVIEW     | 1.30 |    13 | 3 | 3 | d13
			REVIEW     | 1.40 |    10 | 2 | 3 | AGAIN | RELEARNING | 1.30 |     1 | 0 | 4 | +10m
			# Intervals are capped at 100 years
			REVIEW     | 2.50 | 30000 | 9 | 0 | GOOD  | REVIEW     | 2.50 | 36500 | 10 | 0 | d36500
			# Relearning after a lapse: one 10-minute step, then back to the post-lapse interval
			RELEARNING | 2.30 |     1 | 0 | 1 | AGAIN | RELEARNING | 2.30 |     1 | 0 | 1 | +10m
			RELEARNING | 2.30 |     1 | 0 | 1 | HARD  | RELEARNING | 2.30 |     1 | 0 | 1 | +10m
			RELEARNING | 2.30 |     1 | 0 | 1 | GOOD  | REVIEW     | 2.30 |     1 | 1 | 1 | d1
			RELEARNING | 2.30 |     1 | 0 | 1 | EASY  | REVIEW     | 2.30 |     2 | 1 | 1 | d2
			""")
	void schedulesEveryTransition(State state, String ease, int interval, int repetitions, int lapses,
			ReviewGrade grade, State nextState, String nextEase, int nextInterval, int nextRepetitions, int nextLapses,
			String nextDue) {
		Card next = SCHEDULER.review(card(state, ease, interval, repetitions, lapses), grade, NOW, ALMATY);

		assertThat(next.state()).isEqualTo(nextState);
		assertThat(next.ease()).isEqualTo(new BigDecimal(nextEase));
		assertThat(next.intervalDays()).isEqualTo(nextInterval);
		assertThat(next.repetitions()).isEqualTo(nextRepetitions);
		assertThat(next.lapses()).isEqualTo(nextLapses);
		assertThat(next.dueAt()).isEqualTo(due(nextDue));
		assertThat(next.lastReviewedAt()).isEqualTo(NOW);
	}

	@ParameterizedTest(name = "reviewed at {0} in {1}: due {2}, today {3}")
	@CsvSource(delimiter = '|', textBlock = """
			# 23:30 in Almaty: the next local day starts 30 minutes later
			2026-11-11T18:30:00Z | Asia/Almaty      | 2026-11-11T19:00:00Z | 2026-11-11
			# The same moment is 13:30 in New York: its next day starts at 05:00 UTC
			2026-11-11T18:30:00Z | America/New_York | 2026-11-12T05:00:00Z | 2026-11-11
			# Just after midnight in Almaty it's already the 12th there
			2026-11-11T19:30:00Z | Asia/Almaty      | 2026-11-12T19:00:00Z | 2026-11-12
			2026-11-11T19:30:00Z | UTC              | 2026-11-12T00:00:00Z | 2026-11-11
			""")
	void reviewsFallDueAtTheStartOfTheLearnersDay(Instant reviewedAt, ZoneId zone, Instant expectedDue,
			LocalDate today) {
		Card learning = new Card(State.LEARNING, SrsState.INITIAL_EASE, 0, 1, 0, reviewedAt, reviewedAt);

		Card next = SCHEDULER.review(learning, ReviewGrade.GOOD, reviewedAt, zone);

		assertThat(next.intervalDays()).isEqualTo(1);
		assertThat(next.dueAt()).isEqualTo(expectedDue);
		assertThat(SrsScheduler.today(reviewedAt, zone)).isEqualTo(today);
	}

	@Test
	void daylightSavingChangesDoNotShiftTheDay() {
		ZoneId berlin = ZoneId.of("Europe/Berlin");
		// Saturday 28 March 2026; clocks go forward on Sunday the 29th
		Instant saturdayNoon = Instant.parse("2026-03-28T11:00:00Z");
		Card review = new Card(State.REVIEW, SrsState.INITIAL_EASE, 1, 1, 0, saturdayNoon, saturdayNoon);

		Card next = SCHEDULER.review(review, ReviewGrade.HARD, saturdayNoon, berlin);

		assertThat(next.intervalDays()).isEqualTo(2);
		// Monday 30 March 00:00 in Berlin is 22:00 UTC on Sunday (summer time, UTC+2)
		assertThat(next.dueAt()).isEqualTo(Instant.parse("2026-03-29T22:00:00Z"));
	}

	@Test
	void harderGradesNeverGiveLongerIntervals() {
		for (int interval = 1; interval <= 400; interval += 3) {
			for (BigDecimal ease = new BigDecimal("1.30"); ease.compareTo(new BigDecimal("3.50")) <= 0; ease = ease
				.add(new BigDecimal("0.10"))) {
				Card review = new Card(State.REVIEW, ease, interval, 2, 0, NOW, NOW);
				int hard = SCHEDULER.review(review, ReviewGrade.HARD, NOW, ALMATY).intervalDays();
				int good = SCHEDULER.review(review, ReviewGrade.GOOD, NOW, ALMATY).intervalDays();
				int easy = SCHEDULER.review(review, ReviewGrade.EASY, NOW, ALMATY).intervalDays();
				assertThat(hard).as("hard at %d/%s", interval, ease).isGreaterThan(interval);
				assertThat(good).as("good at %d/%s", interval, ease).isGreaterThan(hard);
				assertThat(easy).as("easy at %d/%s", interval, ease).isGreaterThan(good);
			}
		}
	}

	@Test
	void isDeterministicAndLeavesItsInputAlone() {
		Card review = card(State.REVIEW, "2.50", 10, 3, 0);
		Card copy = new Card(review.state(), review.ease(), review.intervalDays(), review.repetitions(),
				review.lapses(), review.dueAt(), review.lastReviewedAt());

		assertThat(SCHEDULER.review(review, ReviewGrade.GOOD, NOW, ALMATY))
			.isEqualTo(SCHEDULER.review(review, ReviewGrade.GOOD, NOW, ALMATY));
		assertThat(review).isEqualTo(copy);
	}

	@Test
	void followsACardThroughItsLife() {
		List<String> log = new ArrayList<>();
		Card card = Card.fresh();
		Instant at = NOW;
		for (ReviewGrade grade : List.of(ReviewGrade.GOOD, ReviewGrade.GOOD, ReviewGrade.GOOD, ReviewGrade.GOOD,
				ReviewGrade.AGAIN, ReviewGrade.GOOD, ReviewGrade.GOOD, ReviewGrade.EASY)) {
			card = SCHEDULER.review(card, grade, at, ALMATY);
			log.add(grade + "→" + card.state() + "/" + card.intervalDays() + "d/" + card.ease());
			at = card.dueAt();
		}
		assertThat(log).containsExactly("GOOD→LEARNING/0d/2.50", // second step, 10 minutes
				"GOOD→REVIEW/1d/2.50", // graduated
				"GOOD→REVIEW/3d/2.50", // max(1+1+1, round(1×2.5))
				"GOOD→REVIEW/8d/2.50", // round(3×2.5) = 8
				"AGAIN→RELEARNING/1d/2.30", // lapse
				"GOOD→REVIEW/1d/2.30", // relearned
				"GOOD→REVIEW/3d/2.30", // max(1+1+1, round(1×2.3))
				"EASY→REVIEW/9d/2.45"); // max(7+1, round(3×2.3×1.3)) = 9
		assertThat(card.lapses()).isEqualTo(1);
	}

	@Test
	void customStepsAndCheckedSettings() {
		SrsScheduler noSteps = new SrsScheduler(new SrsScheduler.Config(List.of(), List.of(), 1, 4,
				new BigDecimal("1.30"), new BigDecimal("0.20"), new BigDecimal("0.15"), new BigDecimal("0.15"), 1.2, 1.3,
				0.5, 36_500));
		// Without learning steps a new card graduates at once
		assertThat(noSteps.review(Card.fresh(), ReviewGrade.AGAIN, NOW, ALMATY).state()).isEqualTo(State.REVIEW);
		// Without relearning steps a lapse goes straight back to review, at half the interval
		Card lapsed = noSteps.review(card(State.REVIEW, "2.50", 10, 3, 0), ReviewGrade.AGAIN, NOW, ALMATY);
		assertThat(lapsed.state()).isEqualTo(State.REVIEW);
		assertThat(lapsed.intervalDays()).isEqualTo(5);
		assertThat(lapsed.lapses()).isEqualTo(1);

		assertThatThrownBy(() -> new SrsScheduler.Config(List.of(), List.of(), 5, 4, BigDecimal.ONE, BigDecimal.ONE,
				BigDecimal.ONE, BigDecimal.ONE, 1, 1, 0, 100))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
