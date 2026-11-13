package com.linguaflow.progress;

import java.time.LocalDate;
import java.util.List;

import com.linguaflow.progress.ActivityStats.Day;
import com.linguaflow.progress.ActivityStats.Week;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityStatsTests {

	/** A Thursday. */
	private static final LocalDate TODAY = LocalDate.parse("2026-11-12");

	private static Day reviews(String date, int reviews, int remembered) {
		return new Day(LocalDate.parse(date), reviews, remembered, 0, 0, 1000L * reviews);
	}

	private static Day exercises(String date, int count, double scoreSum) {
		return new Day(LocalDate.parse(date), 0, 0, count, scoreSum, 5000L * count);
	}

	@Test
	void everyDayInTheRangeIsThereWithZerosForGaps() {
		List<Day> days = ActivityStats.daysBetween(
				List.of(reviews("2026-11-10", 4, 3), exercises("2026-11-10", 2, 1.5), reviews("2026-11-01", 9, 9)),
				LocalDate.parse("2026-11-09"), TODAY);
		assertThat(days).extracting(Day::date)
			.containsExactly(LocalDate.parse("2026-11-09"), LocalDate.parse("2026-11-10"),
					LocalDate.parse("2026-11-11"), TODAY);
		// The same day from reviews and exercises is one day
		assertThat(days.get(1)).isEqualTo(new Day(LocalDate.parse("2026-11-10"), 4, 3, 2, 1.5, 14_000));
		assertThat(days.get(0)).isEqualTo(Day.empty(LocalDate.parse("2026-11-09")));
	}

	@Test
	void weeksStartOnMondayAndRateTheirAnswers() {
		List<Week> weeks = ActivityStats.weeks(List.of(reviews("2026-11-09", 8, 6), reviews("2026-11-12", 2, 2),
				exercises("2026-11-11", 4, 3), reviews("2026-11-08", 5, 1), reviews("2026-10-01", 3, 3)), TODAY, 3);
		assertThat(weeks).extracting(Week::weekStart)
			.containsExactly(LocalDate.parse("2026-10-26"), LocalDate.parse("2026-11-02"),
					LocalDate.parse("2026-11-09"));
		// Nothing that week
		assertThat(weeks.get(0)).isEqualTo(new Week(LocalDate.parse("2026-10-26"), 0, 0, null, 0, null));
		// Sunday the 8th still belongs to the week before
		assertThat(weeks.get(1)).isEqualTo(new Week(LocalDate.parse("2026-11-02"), 5, 1, 0.2, 0, null));
		assertThat(weeks.get(2)).isEqualTo(new Week(LocalDate.parse("2026-11-09"), 10, 8, 0.8, 4, 0.75));
	}

	@Test
	void streaksCountDaysWithAnyPractice() {
		List<Day> days = List.of(reviews("2026-11-11", 3, 3), exercises("2026-11-10", 1, 1), reviews("2026-11-09", 1, 0),
				reviews("2026-11-05", 1, 1), reviews("2026-11-04", 1, 1), reviews("2026-11-03", 1, 1),
				reviews("2026-11-02", 1, 1), Day.empty(TODAY));
		// Today has none yet: the streak runs up to yesterday
		assertThat(ActivityStats.currentStreak(days, TODAY)).isEqualTo(3);
		assertThat(ActivityStats.longestStreak(days)).isEqualTo(4);
		assertThat(ActivityStats.currentStreak(days, TODAY.plusDays(2))).isZero();
		assertThat(ActivityStats.currentStreak(List.of(), TODAY)).isZero();
	}

}
