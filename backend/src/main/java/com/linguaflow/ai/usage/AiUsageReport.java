package com.linguaflow.ai.usage;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import com.linguaflow.ai.usage.AiUsageDtos.ByDay;
import com.linguaflow.ai.usage.AiUsageDtos.ByModel;
import com.linguaflow.ai.usage.AiUsageDtos.ByUseCase;
import com.linguaflow.ai.usage.AiUsageDtos.Summary;
import com.linguaflow.ai.usage.AiUsageDtos.TopUser;
import com.linguaflow.ai.usage.AiUsageDtos.Totals;
import com.linguaflow.common.error.InvalidFieldException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The admin's AI usage summary (AI-05), over UTC days. */
@Service
public class AiUsageReport {

	/** The longest range one summary may cover. */
	static final int MAX_DAYS = 366;

	static final int DEFAULT_DAYS = 30;

	private final AiRequestRepository requests;

	private final Clock clock;

	public AiUsageReport(AiRequestRepository requests, Clock clock) {
		this.requests = requests;
		this.clock = clock;
	}

	/**
	 * @param from first day, inclusive; defaults to 29 days before {@code to}
	 * @param to last day, inclusive; defaults to today (UTC)
	 */
	@Transactional(readOnly = true)
	public Summary summary(@Nullable LocalDate from, @Nullable LocalDate to) {
		LocalDate last = to != null ? to : LocalDate.now(clock.withZone(ZoneOffset.UTC));
		LocalDate first = from != null ? from : last.minusDays(DEFAULT_DAYS - 1);
		if (first.isAfter(last)) {
			throw new InvalidFieldException("from", "must not be after to");
		}
		if (ChronoUnit.DAYS.between(first, last) >= MAX_DAYS) {
			throw new InvalidFieldException("from", "must be at most " + MAX_DAYS + " days before to");
		}
		var start = first.atStartOfDay(ZoneOffset.UTC).toInstant();
		var end = last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		List<AiRequestRepository.UsageRow> rows = requests.summarizeForAdmin(start, end);

		Totals totals = Totals.EMPTY;
		for (AiRequestRepository.UsageRow row : rows) {
			totals = totals.plus(row);
		}
		return new Summary(first, last, totals,
				group(rows, AiRequestRepository.UsageRow::getUseCase).entrySet()
					.stream()
					.map(e -> new ByUseCase(e.getKey(), e.getValue()))
					.toList(),
				group(rows, AiRequestRepository.UsageRow::getModel).entrySet()
					.stream()
					.map(e -> new ByModel(e.getKey(), e.getValue()))
					.toList(),
				group(rows, AiRequestRepository.UsageRow::getDay).entrySet()
					.stream()
					.map(e -> new ByDay(e.getKey(), e.getValue()))
					.toList(),
				requests.topUsersForAdmin(start, end, 10)
					.stream()
					.map(u -> new TopUser(u.getUserId(), u.getRequests(), u.getCost().setScale(6, RoundingMode.HALF_UP)))
					.toList());
	}

	private static <K extends Comparable<? super K>> Map<K, Totals> group(List<AiRequestRepository.UsageRow> rows,
			Function<AiRequestRepository.UsageRow, K> key) {
		Map<K, Totals> grouped = new TreeMap<>();
		rows.forEach(row -> grouped.merge(key.apply(row), Totals.EMPTY.plus(row), Totals::plus));
		return grouped;
	}

}
