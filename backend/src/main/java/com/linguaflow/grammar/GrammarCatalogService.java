package com.linguaflow.grammar;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.common.error.NotFoundException;
import com.linguaflow.grammar.GrammarTopicService.NewGrammarTopic;
import com.linguaflow.user.ActiveLanguageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The catalog of suggested grammar topics (GRM-03): learners pick from it instead of
 * typing. An entry counts as already added when one of the learner's topics was picked
 * from it or has the same title (after {@link TitleNormalizer normalization}), since
 * adding it again would be a duplicate anyway.
 */
@Service
public class GrammarCatalogService {

	/** A catalog entry and, if the learner has it, their topic's ID. */
	public record CatalogItem(GrammarTopicCatalogEntry entry, UUID topicId) {
	}

	private final GrammarTopicCatalogRepository catalog;

	private final GrammarTopicRepository topics;

	private final GrammarTopicService topicService;

	private final ActiveLanguageService activeLanguages;

	GrammarCatalogService(GrammarTopicCatalogRepository catalog, GrammarTopicRepository topics,
			GrammarTopicService topicService, ActiveLanguageService activeLanguages) {
		this.catalog = catalog;
		this.topics = topics;
		this.topicService = topicService;
		this.activeLanguages = activeLanguages;
	}

	/** The active language's catalog, by level then title; empty {@code levels} means all. */
	@Transactional(readOnly = true)
	public List<CatalogItem> list(UUID userId, Set<CefrLevel> levels) {
		String language = activeLanguages.requireActive(userId);
		List<GrammarTopicKey> mine = topics.findKeys(userId, language);
		Map<UUID, UUID> byCatalogId = mine.stream()
			.filter(key -> key.catalogTopicId() != null)
			.collect(Collectors.toMap(GrammarTopicKey::catalogTopicId, GrammarTopicKey::id, (a, b) -> a));
		Map<String, UUID> byTitle = mine.stream()
			.collect(Collectors.toMap(GrammarTopicKey::normalizedTitle, GrammarTopicKey::id, (a, b) -> a));
		Function<GrammarTopicCatalogEntry, UUID> topicOf = entry -> {
			UUID picked = byCatalogId.get(entry.getId());
			return picked != null ? picked : byTitle.get(TitleNormalizer.normalize(language, entry.getTitle()));
		};
		return catalog.findAllInLanguage(language)
			.stream()
			.filter(entry -> levels.isEmpty() || levels.contains(entry.getCefrLevel()))
			.map(entry -> new CatalogItem(entry, topicOf.apply(entry)))
			.toList();
	}

	/**
	 * Adds the entry as one of the learner's topics, with its title, description and level.
	 * @throws NotFoundException if there's no such entry in the active language
	 * @throws DuplicateGrammarTopicException if the learner already has a topic with that title
	 */
	public GrammarTopic add(UUID userId, UUID catalogId, KnowledgeStatus status) {
		String language = activeLanguages.requireActive(userId);
		GrammarTopicCatalogEntry entry = catalog.findByIdAndLanguageCode(catalogId, language)
			.orElseThrow(() -> new NotFoundException("Resource not found"));
		return topicService.create(userId, new NewGrammarTopic(entry.getTitle(), entry.getDescription(), null,
				entry.getCefrLevel(), status, entry.getId()));
	}

}
