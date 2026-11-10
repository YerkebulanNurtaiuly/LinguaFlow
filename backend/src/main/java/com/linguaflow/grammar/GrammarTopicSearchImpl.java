package com.linguaflow.grammar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.linguaflow.learning.SessionGrammar;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Criteria implementation of {@link GrammarTopicSearch}; Spring Data picks it up by name. */
class GrammarTopicSearchImpl implements GrammarTopicSearch {

	private static final char LIKE_ESCAPE = '\\';

	@PersistenceContext
	private EntityManager em;

	@Override
	public Page<GrammarTopic> search(UUID userId, String languageCode, GrammarTopicFilter filter,
			Pageable pageable) {
		CriteriaBuilder cb = em.getCriteriaBuilder();

		CriteriaQuery<GrammarTopic> select = cb.createQuery(GrammarTopic.class);
		Root<GrammarTopic> topic = select.from(GrammarTopic.class);
		select.select(topic)
			.where(predicates(cb, select, topic, userId, languageCode, filter))
			.orderBy(orders(cb, topic, pageable.getSort()));
		List<GrammarTopic> content = em.createQuery(select)
			.setFirstResult((int) pageable.getOffset())
			.setMaxResults(pageable.getPageSize())
			.getResultList();

		CriteriaQuery<Long> count = cb.createQuery(Long.class);
		Root<GrammarTopic> counted = count.from(GrammarTopic.class);
		count.select(cb.count(counted)).where(predicates(cb, count, counted, userId, languageCode, filter));
		long total = em.createQuery(count).getSingleResult();

		return new PageImpl<>(content, pageable, total);
	}

	private static Predicate[] predicates(CriteriaBuilder cb, CriteriaQuery<?> query, Root<GrammarTopic> topic,
			UUID userId,
			String languageCode, GrammarTopicFilter filter) {
		List<Predicate> where = new ArrayList<>();
		where.add(cb.equal(topic.get("userId"), userId));
		where.add(cb.equal(topic.get("languageCode"), languageCode));
		if (filter.query() != null) {
			String titlePattern = contains(TitleNormalizer.normalize(languageCode, filter.query()));
			String descriptionPattern = contains(filter.query().toLowerCase(Locale.ROOT));
			where.add(cb.or(cb.like(topic.get("normalizedTitle"), titlePattern, LIKE_ESCAPE),
					cb.like(cb.lower(topic.get("description")), descriptionPattern, LIKE_ESCAPE)));
		}
		if (!filter.cefrLevels().isEmpty()) {
			where.add(topic.get("cefrLevel").in(filter.cefrLevels()));
		}
		if (!filter.statuses().isEmpty()) {
			where.add(topic.get("status").in(filter.statuses()));
		}
		if (filter.sessionId() != null) {
			// Only the caller's topics can match, so the session's owner needn't be checked
			Subquery<UUID> inSession = query.subquery(UUID.class);
			Root<SessionGrammar> link = inSession.from(SessionGrammar.class);
			inSession.select(link.get("id").get("grammarTopicId"))
				.where(cb.equal(link.get("id").get("studySessionId"), filter.sessionId()),
						cb.equal(link.get("id").get("grammarTopicId"), topic.get("id")));
			where.add(cb.exists(inSession));
		}
		return where.toArray(Predicate[]::new);
	}

	private static List<Order> orders(CriteriaBuilder cb, Root<GrammarTopic> topic, Sort sort) {
		List<Order> orders = new ArrayList<>();
		for (Sort.Order order : sort) {
			orders.add(order.isAscending() ? cb.asc(topic.get(order.getProperty()))
					: cb.desc(topic.get(order.getProperty())));
		}
		orders.add(cb.asc(topic.get("id")));
		return orders;
	}

	/** {@code %text%} with LIKE wildcards in the text escaped. */
	private static String contains(String text) {
		String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
		return "%" + escaped + "%";
	}

}
