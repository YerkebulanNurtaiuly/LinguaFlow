package com.linguaflow.flashcard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Streaks (SRS-03) on the learner's calendar, today being 2026-11-11. */
class ReviewStreakTests {

	private static final LocalDate TODAY = LocalDate.parse("2026-11-11");

	private static List<LocalDate> days(String days) {
		return days.isBlank() ? List.of() : Arrays.stream(days.split(" ")).map(LocalDate::parse).toList();
	}

	@ParameterizedTest(name = "[{0}] → current {1}, longest {2}")
	@CsvSource(delimiter = '|', textBlock = """
			# No reviews at all
			''                                               | 0 | 0
			# Today only
			2026-11-11                                       | 1 | 1
			# Up to yesterday: the streak lives on until today ends
			2026-11-09 2026-11-10                            | 2 | 2
			# Missed yesterday: broken
			2026-11-08 2026-11-09                            | 0 | 2
			# Up to today, with a gap before
			2026-11-01 2026-11-02 2026-11-03 2026-11-10 2026-11-11 | 2 | 3
			# Unordered and repeated days count once
			2026-11-11 2026-11-09 2026-11-10 2026-11-10      | 3 | 3
			# Across a month and a year
			2025-12-31 2026-01-01                            | 0 | 2
			""")
	void countsDaysInARow(String reviewDays, int current, int longest) {
		List<LocalDate> days = days(reviewDays);
		assertThat(ReviewService.currentStreak(days, TODAY)).isEqualTo(current);
		assertThat(ReviewService.longestStreak(days)).isEqualTo(longest);
	}

}
