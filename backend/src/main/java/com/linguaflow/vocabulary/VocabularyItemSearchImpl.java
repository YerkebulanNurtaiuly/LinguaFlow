package com.linguaflow.vocabulary;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.linguaflow.learning.SessionVocabulary;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Criteria implementation of {@link VocabularyItemSearch}; Spring Data picks it up by name. */
class VocabularyItemSearchImpl implements VocabularyItemSearch {

	private static final char LIKE_ESCAPE = '\\';

	@PersistenceContext
	private EntityManager em;

	@Override
	public Page<VocabularyItem> search(UUID userId, String languageCode, VocabularyFilter filter,
			Pageable pageable) {
		CriteriaBuilder cb = em.getCriteriaBuilder();

		CriteriaQuery<VocabularyItem> select = cb.createQuery(VocabularyItem.class);
		Root<VocabularyItem> item = select.from(VocabularyItem.class);
		select.select(item)
			.where(predicates(cb, select, item, userId, languageCode, filter))
			.orderBy(orders(cb, item, pageable.getSort()));
		List<VocabularyItem> content = em.createQuery(select)
			.setFirstResult((int) pageable.getOffset())
			.setMaxResults(pageable.getPageSize())
			.getResultList();

		CriteriaQuery<Long> count = cb.createQuery(Long.class);
		Root<VocabularyItem> counted = count.from(VocabularyItem.class);
		count.select(cb.count(counted)).where(predicates(cb, count, counted, userId, languageCode, filter));
		long total = em.createQuery(count).getSingleResult();

		return new PageImpl<>(content, pageable, total);
	}

	private static Predicate[] predicates(CriteriaBuilder cb, CriteriaQuery<?> query, Root<VocabularyItem> item,
			UUID userId, String languageCode, VocabularyFilter filter) {
		List<Predicate> where = new ArrayList<>();
		where.add(cb.equal(item.get("userId"), userId));
		where.add(cb.equal(item.get("languageCode"), languageCode));

		if (filter.query() != null) {
			String lemmaPattern = contains(LemmaNormalizer.normalize(languageCode, filter.query()));
			String translationPattern = contains(filter.query().toLowerCase(Locale.ROOT));
			where.add(cb.or(cb.like(item.get("normalizedLemma"), lemmaPattern, LIKE_ESCAPE),
					cb.like(cb.lower(item.get("translation")), translationPattern, LIKE_ESCAPE)));
		}
		if (!filter.partsOfSpeech().isEmpty()) {
			where.add(item.get("partOfSpeech").in(filter.partsOfSpeech()));
		}
		if (!filter.cefrLevels().isEmpty()) {
			where.add(item.get("cefrLevel").in(filter.cefrLevels()));
		}
		if (!filter.statuses().isEmpty()) {
			where.add(item.get("status").in(filter.statuses()));
		}
		if (!filter.aiStatuses().isEmpty()) {
			where.add(item.get("aiStatus").in(filter.aiStatuses()));
		}
		if (filter.tagId() != null) {
			Subquery<UUID> tagged = query.subquery(UUID.class);
			Root<VocabularyItem> sameItem = tagged.correlate(item);
			Join<VocabularyItem, Tag> tag = sameItem.join("tags");
			tagged.select(tag.get("id")).where(cb.equal(tag.get("id"), filter.tagId()));
			where.add(cb.exists(tagged));
		}
		if (filter.sessionId() != null) {
			// The session's own owner check isn't needed: only the caller's items can match
			Subquery<UUID> inSession = query.subquery(UUID.class);
			Root<SessionVocabulary> link = inSession.from(SessionVocabulary.class);
			inSession.select(link.get("id").get("vocabularyItemId"))
				.where(cb.equal(link.get("id").get("studySessionId"), filter.sessionId()),
						cb.equal(link.get("id").get("vocabularyItemId"), item.get("id")));
			where.add(cb.exists(inSession));
		}
		return where.toArray(Predicate[]::new);
	}

	private static List<Order> orders(CriteriaBuilder cb, Root<VocabularyItem> item, Sort sort) {
		List<Order> orders = new ArrayList<>();
		for (Sort.Order order : sort) {
			orders.add(order.isAscending() ? cb.asc(item.get(order.getProperty()))
					: cb.desc(item.get(order.getProperty())));
		}
		orders.add(cb.asc(item.get("id")));
		return orders;
	}

	/** {@code %text%} with LIKE wildcards in the text escaped, so "50%" matches literally. */
	private static String contains(String text) {
		String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
		return "%" + escaped + "%";
	}

}
