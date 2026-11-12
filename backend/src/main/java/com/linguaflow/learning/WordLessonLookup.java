package com.linguaflow.learning;

import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.linguaflow.common.practice.WordLessons;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link WordLessons} from the study sessions' word links (PRC-03). */
@Component
class WordLessonLookup implements WordLessons {

	/** "2 Nov": a short date, which prompt safety (AI-07) doesn't mistake for a phone number. */
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

	private final NamedParameterJdbcTemplate jdbc;

	WordLessonLookup(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> latestLessons(UUID userId, Collection<UUID> vocabularyItemIds) {
		Map<UUID, String> lessons = new HashMap<>();
		if (vocabularyItemIds.isEmpty()) {
			return lessons;
		}
		jdbc.query("""
				select distinct on (sv.vocabulary_item_id) sv.vocabulary_item_id, s.title, s.studied_on
				  from session_vocabulary sv
				  join study_sessions s on s.id = sv.study_session_id
				 where s.user_id = :userId and sv.vocabulary_item_id in (:ids)
				 order by sv.vocabulary_item_id, s.studied_on desc, s.created_at desc, s.id desc
				""", new MapSqlParameterSource("userId", userId).addValue("ids", vocabularyItemIds),
				rs -> {
					lessons.put(rs.getObject("vocabulary_item_id", UUID.class),
							rs.getString("title") + " (" + DAY.format(rs.getDate("studied_on").toLocalDate()) + ")");
				});
		return lessons;
	}

}
