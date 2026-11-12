package com.linguaflow.progress;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.config.LinguaFlowProperties;
import com.linguaflow.common.config.LinguaFlowProperties.Mastery;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.grammar.GrammarTopicService;
import com.linguaflow.grammar.StatusChangeReason;
import com.linguaflow.vocabulary.VocabularyService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the mastery rules (PRG-02) to topics and words after their progress changed.
 * Topics change through {@link GrammarTopicService#changeStatusByRule}, so the change is
 * in their status history (MASTERY_RULE or DEMOTION_RULE); words through
 * {@link VocabularyService#changeStatusByRule}.
 */
@Service
public class MasteryService {

	private static final Logger log = LoggerFactory.getLogger(MasteryService.class);

	/** How many statuses one run changed. */
	public record Changes(int topics, int words) {
	}

	private final NamedParameterJdbcTemplate jdbc;

	private final GrammarTopicService topics;

	private final VocabularyService vocabulary;

	private final Mastery rules;

	MasteryService(NamedParameterJdbcTemplate jdbc, GrammarTopicService topics, VocabularyService vocabulary,
			LinguaFlowProperties properties) {
		this.jdbc = jdbc;
		this.topics = topics;
		this.vocabulary = vocabulary;
		this.rules = properties.progress().mastery();
	}

	/**
	 * Applies the rules to the user's topics and words with progress; {@code null} means
	 * all of them, an empty collection none.
	 */
	@Transactional
	public Changes apply(UUID userId, @Nullable Collection<UUID> topicIds, @Nullable Collection<UUID> wordIds) {
		int topicChanges = 0;
		if (topicIds == null || !topicIds.isEmpty()) {
			for (TopicRow row : topicRows(userId, topicIds)) {
				var next = MasteryRules.topic(row.status(), row.record(), rules);
				if (next.isPresent() && topics.changeStatusByRule(userId, row.id(), next.get(),
						next.get() == KnowledgeStatus.MASTERED ? StatusChangeReason.MASTERY_RULE
								: StatusChangeReason.DEMOTION_RULE)) {
					topicChanges++;
					log.info("Grammar topic {} is now {} by practice", row.id(), next.get());
				}
			}
		}
		int wordChanges = 0;
		if (wordIds == null || !wordIds.isEmpty()) {
			for (WordRow row : wordRows(userId, wordIds)) {
				var next = MasteryRules.word(row.status(), row.intervalDays(), rules);
				if (next.isPresent() && vocabulary.changeStatusByRule(userId, row.id(), next.get())) {
					wordChanges++;
				}
			}
		}
		return new Changes(topicChanges, wordChanges);
	}

	private record TopicRow(UUID id, KnowledgeStatus status, MasteryRules.TopicRecord record) {
	}

	private record WordRow(UUID id, KnowledgeStatus status, int intervalDays) {
	}

	private List<TopicRow> topicRows(UUID userId, @Nullable Collection<UUID> ids) {
		return jdbc.query("""
				select p.grammar_topic_id, t.status, p.attempts_total, p.recent_accuracy, p.practice_days
				  from grammar_topic_progress p
				  join grammar_topics t on t.id = p.grammar_topic_id
				 where p.user_id = :userId""" + (ids == null ? "" : " and p.grammar_topic_id in (:ids)"),
				new MapSqlParameterSource("userId", userId).addValue("ids", ids),
				(rs, n) -> new TopicRow(rs.getObject("grammar_topic_id", UUID.class),
						KnowledgeStatus.valueOf(rs.getString("status")),
						new MasteryRules.TopicRecord(rs.getInt("attempts_total"),
								rs.getBigDecimal("recent_accuracy") == null ? null
										: rs.getBigDecimal("recent_accuracy").doubleValue(),
								rs.getInt("practice_days"))));
	}

	private List<WordRow> wordRows(UUID userId, @Nullable Collection<UUID> ids) {
		return jdbc.query("""
				select p.vocabulary_item_id, v.status, p.interval_days
				  from vocabulary_progress p
				  join vocabulary_items v on v.id = p.vocabulary_item_id
				 where p.user_id = :userId""" + (ids == null ? "" : " and p.vocabulary_item_id in (:ids)"),
				new MapSqlParameterSource("userId", userId).addValue("ids", ids),
				(rs, n) -> new WordRow(rs.getObject("vocabulary_item_id", UUID.class),
						KnowledgeStatus.valueOf(rs.getString("status")), rs.getInt("interval_days")));
	}

}
