package com.linguaflow.progress;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.common.security.CurrentUserId;
import com.linguaflow.progress.WeaknessScores.ReasonCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Progress analysis: statistics (PRG-05), weak points (PRG-03) and frequent mistakes (PRG-04). */
@RestController
@RequestMapping("/api/progress")
@Tag(name = "Progress", description = "How your learning is going")
class ProgressController {

	private final WeakPointService weakPoints;

	private final FrequentMistakeService mistakes;

	private final StatisticsService statistics;

	ProgressController(WeakPointService weakPoints, FrequentMistakeService mistakes, StatisticsService statistics) {
		this.statistics = statistics;
		this.weakPoints = weakPoints;
		this.mistakes = mistakes;
	}

	@Schema(name = "WeakPointReason", description = "Why it counts as weak, with the number behind it")
	record ReasonResponse(ReasonCode code,
			@Schema(description = "LOW_ACCURACY: the recent accuracy (0–1); FREQUENT_MISTAKE: how many mistakes "
					+ "of errorType; LAPSES: the most lapses on one card; NOT_PRACTISED_RECENTLY: days since") double value,
			@Schema(description = "FREQUENT_MISTAKE only: the error type, e.g. WRONG_CASE") String errorType) {
	}

	@Schema(name = "WeakPoint")
	record WeakPointResponse(UUID id, @Schema(description = "The topic's title or the word") String name,
			@Schema(description = "Words only") String translation, KnowledgeStatus status,
			@Schema(description = "0 (solid) to 1 (weakest)") double score,
			@Schema(description = "Attempts (topics) or answers (words) counted") int answers,
			@Schema(description = "The recent accuracy, 0–1") Double recentAccuracy, Instant lastPracticedAt,
			List<ReasonResponse> reasons) {

		static WeakPointResponse from(WeakPointService.WeakPoint point) {
			return new WeakPointResponse(point.id(), point.name(), point.translation(), point.status(),
					point.weakness().score(), point.answers(), point.recentAccuracy(), point.lastPracticedAt(),
					point.weakness()
						.reasons()
						.stream()
						.map(reason -> new ReasonResponse(reason.code(), reason.value(), reason.errorType()))
						.toList());
		}

	}

	@Schema(name = "WeakPoints")
	record WeakPointsResponse(@Schema(description = "Weakest first") List<WeakPointResponse> grammarTopics,
			@Schema(description = "Weakest first") List<WeakPointResponse> words) {
	}

	@Schema(name = "MistakeTopic")
	record TopicResponse(UUID id, String title) {

		static TopicResponse from(FrequentMistakeService.Topic topic) {
			return topic == null ? null : new TopicResponse(topic.id(), topic.title());
		}

	}

	@Schema(name = "MistakeSlice",
			description = "Part of a group: a topic in an error-type group, or an error type in a topic group")
	record SliceResponse(String errorType,
			@Schema(description = "Null for mistakes without a topic, e.g. a wrong word") TopicResponse grammarTopic,
			int count) {
	}

	@Schema(name = "MistakeAttempt", description = "The answer the mistake was made in")
	record AttemptResponse(UUID exerciseId, ExerciseType exerciseType,
			@Schema(description = "The exercise as you saw it") String exercise,
			@Schema(description = "What you answered") String answer, String correctAnswer) {
	}

	@Schema(name = "MistakeExample", description = "One of your wrong answers")
	record ExampleResponse(UUID mistakeId, String errorType,
			@Schema(description = "The wrong part, e.g. what you typed into a gap") String excerpt,
			@Schema(description = "Why it's wrong, when the AI graded it") String explanation, Instant madeAt,
			TopicResponse grammarTopic, AttemptResponse attempt) {
	}

	@Schema(name = "MistakeGroup")
	record GroupResponse(@Schema(description = "TYPE grouping: the error type, e.g. WRONG_CASE") String errorType,
			@Schema(description = "TOPIC grouping: the topic; null for mistakes without one") TopicResponse grammarTopic,
			int count, Instant lastSeenAt,
			@Schema(description = "The other dimension, most frequent first") List<SliceResponse> breakdown,
			@Schema(description = "The latest, newest first") List<ExampleResponse> examples) {

		static GroupResponse from(FrequentMistakeService.Group group) {
			return new GroupResponse(group.errorType(), TopicResponse.from(group.topic()), group.count(),
					group.lastSeenAt(),
					group.breakdown()
						.stream()
						.map(slice -> new SliceResponse(slice.errorType(), TopicResponse.from(slice.topic()),
								slice.count()))
						.toList(),
					group.examples().stream().map(example -> {
						var attempt = example.attempt();
						return new ExampleResponse(example.mistakeId(), example.errorType(), example.excerpt(),
								example.explanation(), example.madeAt(), TopicResponse.from(example.topic()),
								attempt == null ? null
										: new AttemptResponse(attempt.exerciseId(), attempt.type(),
												attempt.exercise(), attempt.answer(), attempt.correctAnswer()));
					}).toList());
		}

	}

	@Schema(name = "FrequentMistakes")
	record MistakesResponse(FrequentMistakeService.Grouping groupBy,
			@Schema(description = "The start of the period; null for all time") Instant since,
			@Schema(description = "Mistakes counted, in every group") int total,
			@Schema(description = "Most frequent first") List<GroupResponse> groups) {
	}

	@GetMapping("/mistakes")
	@Operation(summary = "Your frequent mistakes",
			description = "The active language's exercise mistakes grouped by error type or by grammar topic, most "
					+ "frequent first. Each group breaks down the other dimension and shows your latest wrong answers.")
	@ApiResponse(responseCode = "200", description = "The mistakes")
	MistakesResponse mistakes(@CurrentUserId UUID userId,
			@Parameter(description = "TYPE (default) or TOPIC") @RequestParam(
					defaultValue = "TYPE") FrequentMistakeService.Grouping groupBy,
			@Parameter(description = "Only the last this many days, 1–" + FrequentMistakeService.MAX_DAYS
					+ "; absent means all time") @RequestParam(required = false) Integer days,
			@Parameter(description = "Only this grammar topic's mistakes") @RequestParam(
					required = false) UUID grammarTopicId,
			@Parameter(description = "At most this many groups, 1–" + FrequentMistakeService.MAX_GROUPS) @RequestParam(
					defaultValue = "10") int limit,
			@Parameter(description = "Examples per group, 0–" + FrequentMistakeService.MAX_EXAMPLES) @RequestParam(
					defaultValue = "3") int examples) {
		if (days != null && (days < 1 || days > FrequentMistakeService.MAX_DAYS)) {
			throw new InvalidFieldException("days", "must be between 1 and " + FrequentMistakeService.MAX_DAYS);
		}
		if (limit < 1 || limit > FrequentMistakeService.MAX_GROUPS) {
			throw new InvalidFieldException("limit", "must be between 1 and " + FrequentMistakeService.MAX_GROUPS);
		}
		if (examples < 0 || examples > FrequentMistakeService.MAX_EXAMPLES) {
			throw new InvalidFieldException("examples", "must be between 0 and " + FrequentMistakeService.MAX_EXAMPLES);
		}
		var result = mistakes.frequent(userId,
				new FrequentMistakeService.Query(groupBy, days, grammarTopicId, limit, examples));
		return new MistakesResponse(result.groupBy(), result.since(), result.total(),
				result.groups().stream().map(GroupResponse::from).toList());
	}

	@Schema(name = "StatusCount")
	record StatusCount(KnowledgeStatus status, int count) {
	}

	@Schema(name = "LevelCount")
	record LevelCount(CefrLevel level, int count) {
	}

	@Schema(name = "MaterialStats", description = "Words or grammar topics by status and CEFR level")
	record MaterialResponse(int total, @Schema(description = "Every status, zeros included") List<StatusCount> byStatus,
			@Schema(description = "A1 to C2, zeros included") List<LevelCount> byLevel,
			@Schema(description = "Without a CEFR level") int unleveled) {

		static MaterialResponse from(StatisticsService.Material material) {
			return new MaterialResponse(material.total(),
					material.byStatus().entrySet().stream().map(e -> new StatusCount(e.getKey(), e.getValue())).toList(),
					material.byLevel().entrySet().stream().map(e -> new LevelCount(e.getKey(), e.getValue())).toList(),
					material.unleveled());
		}

	}

	@Schema(name = "ActivityDay", description = "One day's practice, on your calendar")
	record DayResponse(LocalDate date, int reviews,
			@Schema(description = "Reviews graded anything but AGAIN") int remembered, int exercises,
			@Schema(description = "Answering time reported by reviews and exercises") long studiedMs) {
	}

	@Schema(name = "AccuracyWeek", description = "One week, Monday first")
	record WeekResponse(LocalDate weekStart, int reviews, int remembered,
			@Schema(description = "remembered / reviews, 0–1; null without reviews") Double reviewRate,
			int exercises,
			@Schema(description = "The average exercise score, 0–1; null without exercises") Double exerciseScore) {
	}

	@Schema(name = "TimeStudied")
	record TimeResponse(long todayMs, @Schema(description = "Over the activity's days") long periodMs,
			long totalMs) {
	}

	@Schema(name = "ProgressStats")
	record StatsResponse(@Schema(description = "Your today, in your time zone") LocalDate today,
			MaterialResponse words, MaterialResponse grammarTopics,
			@Schema(description = "Every day from `from` to today, zeros included, for the heatmap") List<DayResponse> activity,
			@Schema(description = "Oldest first, this week last") List<WeekResponse> accuracy,
			@Schema(description = "Days in a row with a review or exercise, ending today, or yesterday while "
					+ "today has none yet") int currentStreak,
			int longestStreak, TimeResponse timeStudied) {

		static StatsResponse from(StatisticsService.Statistics stats) {
			return new StatsResponse(stats.today(), MaterialResponse.from(stats.words()),
					MaterialResponse.from(stats.grammarTopics()),
					stats.activity()
						.stream()
						.map(day -> new DayResponse(day.date(), day.reviews(), day.remembered(), day.exercises(),
								day.studiedMs()))
						.toList(),
					stats.accuracy()
						.stream()
						.map(week -> new WeekResponse(week.weekStart(), week.reviews(), week.remembered(),
								week.reviewRate(), week.exercises(), week.exerciseScore()))
						.toList(),
					stats.currentStreak(), stats.longestStreak(), new TimeResponse(stats.timeStudied().todayMs(),
							stats.timeStudied().periodMs(), stats.timeStudied().totalMs()));
		}

	}

	@GetMapping("/stats")
	@Operation(summary = "Your statistics",
			description = "For the dashboard, in the active language and on your calendar: words and grammar topics "
					+ "by status and CEFR level, reviews and exercises per day (a heatmap), the weekly accuracy "
					+ "trend, your practice streak and time studied.")
	@ApiResponse(responseCode = "200", description = "The statistics")
	StatsResponse stats(@CurrentUserId UUID userId,
			@Parameter(description = "Days in the activity heatmap, " + StatisticsService.MIN_DAYS + "–"
					+ StatisticsService.MAX_DAYS) @RequestParam(defaultValue = "90") int days,
			@Parameter(description = "Weeks in the accuracy trend, " + StatisticsService.MIN_WEEKS + "–"
					+ StatisticsService.MAX_WEEKS) @RequestParam(defaultValue = "12") int weeks) {
		if (days < StatisticsService.MIN_DAYS || days > StatisticsService.MAX_DAYS) {
			throw new InvalidFieldException("days",
					"must be between " + StatisticsService.MIN_DAYS + " and " + StatisticsService.MAX_DAYS);
		}
		if (weeks < StatisticsService.MIN_WEEKS || weeks > StatisticsService.MAX_WEEKS) {
			throw new InvalidFieldException("weeks",
					"must be between " + StatisticsService.MIN_WEEKS + " and " + StatisticsService.MAX_WEEKS);
		}
		return StatsResponse.from(statistics.statistics(userId, days, weeks));
	}

	@GetMapping("/weak-points")
	@Operation(summary = "Your weak points",
			description = "The active language's weakest grammar topics and words that you have practised, weakest "
					+ "first, each with why: low recent accuracy, a frequent mistake, card lapses or a long break. "
					+ "Scores below " + WeakPointService.MIN_SCORE + " aren't listed.")
	@ApiResponse(responseCode = "200", description = "The weak points")
	WeakPointsResponse weakPoints(@CurrentUserId UUID userId,
			@Parameter(description = "At most this many of each, 1–" + WeakPointService.MAX_LIMIT) @RequestParam(
					defaultValue = "10") int limit) {
		if (limit < 1 || limit > WeakPointService.MAX_LIMIT) {
			throw new InvalidFieldException("limit", "must be between 1 and " + WeakPointService.MAX_LIMIT);
		}
		var found = weakPoints.weakPoints(userId, limit);
		return new WeakPointsResponse(found.topics().stream().map(WeakPointResponse::from).toList(),
				found.words().stream().map(WeakPointResponse::from).toList());
	}

}
