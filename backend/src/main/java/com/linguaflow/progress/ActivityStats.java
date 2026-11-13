package com.linguaflow.progress;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

/**
 * Day-by-day practice figures for the statistics (PRG-05): the heatmap, the weekly
 * accuracy trend and the streaks. Pure: the days come from the database already on the
 * learner's calendar.
 */
public final class ActivityStats {

	private ActivityStats() {
	}

	/**
	 * One day's practice.
	 * @param remembered reviews graded anything but AGAIN
	 * @param scoreSum the exercise attempts' scores added up
	 * @param studiedMs the answering time reported by reviews and attempts
	 */
	public record Day(LocalDate date, int reviews, int remembered, int exercises, double scoreSum, long studiedMs) {

		public static Day empty(LocalDate date) {
			return new Day(date, 0, 0, 0, 0, 0);
		}

		boolean active() {
			return reviews > 0 || exercises > 0;
		}

		Day plus(Day other) {
			return new Day(date, reviews + other.reviews, remembered + other.remembered, exercises + other.exercises,
					scoreSum + other.scoreSum, studiedMs + other.studiedMs);
		}

	}

	/**
	 * One week, Monday first.
	 * @param reviewRate remembered / reviews; null without reviews
	 * @param exerciseScore the average exercise score; null without attempts
	 */
	public record Week(LocalDate weekStart, int reviews, int remembered, @Nullable Double reviewRate, int exercises,
			@Nullable Double exerciseScore) {
	}

	/** Every day from {@code from} to {@code to}, with zeros for days without practice. */
	public static List<Day> daysBetween(Collection<Day> days, LocalDate from, LocalDate to) {
		Map<LocalDate, Day> byDate = index(days);
		List<Day> result = new ArrayList<>();
		for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
			result.add(byDate.getOrDefault(date, Day.empty(date)));
		}
		return result;
	}

	/** The last {@code count} weeks up to the one containing {@code today}, oldest first. */
	public static List<Week> weeks(Collection<Day> days, LocalDate today, int count) {
		LocalDate thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		Map<LocalDate, Day> byWeek = new HashMap<>();
		for (Day day : days) {
			LocalDate week = day.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
			byWeek.merge(week, day, Day::plus);
		}
		List<Week> result = new ArrayList<>();
		for (int i = count - 1; i >= 0; i--) {
			LocalDate start = thisWeek.minusWeeks(i);
			Day total = byWeek.getOrDefault(start, Day.empty(start));
			result.add(new Week(start, total.reviews(), total.remembered(),
					total.reviews() == 0 ? null : round(total.remembered() / (double) total.reviews()),
					total.exercises(), total.exercises() == 0 ? null : round(total.scoreSum() / total.exercises())));
		}
		return result;
	}

	/** Days in a row with practice up to today, or up to yesterday while today has none yet. */
	public static int currentStreak(Collection<Day> days, LocalDate today) {
		TreeSet<LocalDate> active = activeDates(days);
		LocalDate day = active.contains(today) ? today : today.minusDays(1);
		int streak = 0;
		while (active.contains(day)) {
			streak++;
			day = day.minusDays(1);
		}
		return streak;
	}

	/** The longest run of consecutive days with practice. */
	public static int longestStreak(Collection<Day> days) {
		int longest = 0;
		int run = 0;
		LocalDate previous = null;
		for (LocalDate day : activeDates(days)) {
			run = previous != null && previous.plusDays(1).equals(day) ? run + 1 : 1;
			longest = Math.max(longest, run);
			previous = day;
		}
		return longest;
	}

	private static TreeSet<LocalDate> activeDates(Collection<Day> days) {
		TreeSet<LocalDate> active = new TreeSet<>();
		days.stream().filter(Day::active).forEach(day -> active.add(day.date()));
		return active;
	}

	private static Map<LocalDate, Day> index(Collection<Day> days) {
		Map<LocalDate, Day> byDate = new HashMap<>();
		days.forEach(day -> byDate.merge(day.date(), day, Day::plus));
		return byDate;
	}

	private static double round(double value) {
		return Math.round(value * 1000) / 1000.0;
	}

}
