package com.linguaflow.vocabulary;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.common.error.NotFoundException;
import com.linguaflow.user.ActiveLanguageService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * User-defined tags for grouping words (VOC-03). Tags belong to one learning language;
 * they are created and listed in the active one. Names are trimmed, inner whitespace is
 * collapsed, and they are unique per language ignoring case.
 */
@Service
public class TagService {

	public static final int MAX_NAME_LENGTH = 50;

	/** Most tags one word can carry. */
	public static final int MAX_TAGS_PER_WORD = 20;

	private final TagRepository tags;

	private final ActiveLanguageService activeLanguages;

	private final TransactionTemplate transactions;

	TagService(TagRepository tags, ActiveLanguageService activeLanguages,
			PlatformTransactionManager transactionManager) {
		this.tags = tags;
		this.activeLanguages = activeLanguages;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/** A tag with the number of words that carry it. */
	public record TagWithCount(Tag tag, long wordCount) {
	}

	/** All tags of the active language, by name, each with its word count. */
	@Transactional(readOnly = true)
	public List<TagWithCount> list(UUID userId) {
		String language = activeLanguages.requireActive(userId);
		Map<UUID, Long> counts = tags.countWords(userId, language)
			.stream()
			.collect(Collectors.toMap(TagUsage::tagId, TagUsage::wordCount));
		return tags.findAllInLanguage(userId, language)
			.stream()
			.map(tag -> new TagWithCount(tag, counts.getOrDefault(tag.getId(), 0L)))
			.toList();
	}

	/**
	 * Creates a tag in the active language.
	 * @throws TagNameTakenException if a tag with that name exists (ignoring case)
	 */
	public TagWithCount create(UUID userId, String rawName) {
		String language = activeLanguages.requireActive(userId);
		String name = cleanName(rawName);
		rejectTaken(userId, language, name, null);
		Tag saved = saveRejectingTaken(() -> tags.saveAndFlush(new Tag(userId, language, name)), userId,
				() -> language, name);
		return new TagWithCount(saved, 0);
	}

	/** Renames a tag; changing only the case of its own name is allowed. */
	public TagWithCount rename(UUID userId, UUID id, String rawName) {
		String name = cleanName(rawName);
		String[] language = new String[1];
		return saveRejectingTaken(() -> transactions.execute(tx -> {
			Tag tag = tags.requireOwned(id, userId);
			language[0] = tag.getLanguageCode();
			rejectTaken(userId, tag.getLanguageCode(), name, tag.getId());
			tag.setName(name);
			Tag saved = tags.saveAndFlush(tag);
			return new TagWithCount(saved, tags.countWordsWithTag(userId, saved.getId()));
		}), userId, () -> language[0], name);
	}

	/** Deletes the tag; its words stay, they just lose the tag. */
	@Transactional
	public void delete(UUID userId, UUID id) {
		if (tags.deleteByIdAndUserId(id, userId) == 0) {
			throw new NotFoundException("Resource not found");
		}
	}

	/**
	 * Loads the user's tags with these IDs in {@code languageCode}, for attaching to a word.
	 * Must run in the caller's transaction so the tags are managed.
	 * @throws InvalidFieldException on "tagIds" if one doesn't exist, belongs to someone
	 * else or to another language, or if there are too many
	 */
	Set<Tag> resolveForWord(UUID userId, String languageCode, Collection<UUID> ids) {
		Set<UUID> wanted = new LinkedHashSet<>(ids);
		if (wanted.size() > MAX_TAGS_PER_WORD) {
			throw new InvalidFieldException("tagIds", "a word can have at most " + MAX_TAGS_PER_WORD + " tags");
		}
		if (wanted.isEmpty()) {
			return Set.of();
		}
		List<Tag> found = tags.findAllInLanguageByIds(userId, languageCode, wanted);
		if (found.size() != wanted.size()) {
			throw new InvalidFieldException("tagIds", "contains a tag that doesn't exist");
		}
		return new LinkedHashSet<>(found);
	}

	/** Same checks as {@link #resolveForWord}, for validating up front (bulk input, VOC-04). */
	@Transactional(readOnly = true)
	public void checkUsable(UUID userId, String languageCode, Collection<UUID> ids) {
		resolveForWord(userId, languageCode, ids);
	}

	static String cleanName(String raw) {
		String cleaned = raw == null ? "" : raw.replaceAll("[\\s\\p{Z}]+", " ").strip();
		if (cleaned.isEmpty()) {
			throw new InvalidFieldException("name", "must not be blank");
		}
		if (cleaned.length() > MAX_NAME_LENGTH) {
			throw new InvalidFieldException("name", "must be at most " + MAX_NAME_LENGTH + " characters");
		}
		return cleaned;
	}

	private void rejectTaken(UUID userId, String language, String name, UUID self) {
		tags.findIdByName(userId, language, name).filter(existing -> !existing.equals(self)).ifPresent(existing -> {
			throw new TagNameTakenException(name, existing);
		});
	}

	/** Like VocabularyService: a concurrent insert of the same name trips the unique index. */
	private <T> T saveRejectingTaken(Supplier<T> save, UUID userId, Supplier<String> languageOf, String name) {
		try {
			return save.get();
		}
		catch (DataIntegrityViolationException ex) {
			// Read after the write ran: a rename only learns the language inside its transaction
			String language = languageOf.get();
			if (language == null || ex.getMessage() == null
					|| !ex.getMessage().contains(TagRepository.NAME_UNIQUE_CONSTRAINT)) {
				throw ex;
			}
			UUID existing = tags.findIdByName(userId, language, name).orElseThrow(() -> ex);
			throw new TagNameTakenException(name, existing);
		}
	}

}
