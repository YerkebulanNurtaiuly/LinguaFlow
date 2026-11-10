package com.linguaflow.learning;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.common.config.ClockConfig;
import com.linguaflow.common.config.JpaAuditingConfig;
import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.grammar.GrammarExplanation;
import com.linguaflow.grammar.GrammarTopic;
import com.linguaflow.grammar.StatusChangeReason;
import com.linguaflow.vocabulary.AiStatus;
import com.linguaflow.vocabulary.Gender;
import com.linguaflow.vocabulary.PartOfSpeech;
import com.linguaflow.vocabulary.Tag;
import com.linguaflow.vocabulary.VocabularyItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trips the DB-03 entities through Hibernate (schema in validate mode) to prove the
 * mappings work beyond startup validation: JSON, arrays, enums, collections, auditing.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, JpaAuditingConfig.class, ClockConfig.class })
@ActiveProfiles("test")
class LearningMaterialMappingTests {

	private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

	@Autowired
	private TestEntityManager em;

	@Autowired
	private JdbcTemplate jdbc;

	private UUID userId;

	@BeforeEach
	void createLearner() {
		// No User entity yet (AUTH-01): create the owning rows directly
		userId = UUID.randomUUID();
		jdbc.update("insert into users (id, email, password_hash, display_name) values (?, ?, 'h', 'T')", userId,
				userId + "@example.de");
		jdbc.update("insert into user_languages (user_id, language_code, explanation_language_code, active) "
				+ "values (?, 'de', 'en', true)", userId);
	}

	@Test
	void vocabularyItemRoundTrip() {
		Tag tag = em.persist(new Tag(userId, "de", "Tiere"));
		VocabularyItem item = new VocabularyItem(userId, "de", "der Hund", "hund");
		item.setPartOfSpeech(PartOfSpeech.NOUN);
		item.setGender(Gender.MASCULINE);
		item.setPlural("die Hunde");
		item.setTranslation("dog");
		item.setCefrLevel(CefrLevel.A1);
		item.setAiStatus(AiStatus.READY);
		item.setAiGeneratedFields(List.of("translation", "plural"));
		item.setExtra(Map.of("note", "regular", "frequency", 42));
		item.addExample("Der Hund bellt.", "The dog barks.", true);
		item.addExample("Ich habe einen Hund.", "I have a dog.", false);
		item.addTag(tag);

		UUID id = em.persistFlushFind(item).getId();
		em.clear();
		VocabularyItem loaded = em.find(VocabularyItem.class, id);

		assertThat(loaded.getId().version()).as("UUIDv7").isEqualTo(7);
		assertThat(loaded.getLemma()).isEqualTo("der Hund");
		assertThat(loaded.getPartOfSpeech()).isEqualTo(PartOfSpeech.NOUN);
		assertThat(loaded.getGender()).isEqualTo(Gender.MASCULINE);
		assertThat(loaded.getStatus()).isEqualTo(KnowledgeStatus.LEARNING);
		assertThat(loaded.getAiGeneratedFields()).containsExactly("translation", "plural");
		assertThat(loaded.getExtra()).containsEntry("note", "regular").containsEntry("frequency", 42);
		assertThat(loaded.getExamples()).extracting(e -> e.getSentence())
			.containsExactly("Der Hund bellt.", "Ich habe einen Hund.");
		assertThat(loaded.getExamples()).extracting(e -> e.getPosition()).containsExactly((short) 0, (short) 1);
		assertThat(loaded.getTags()).extracting(Tag::getName).containsExactly("Tiere");
		assertThat(loaded.getCreatedAt()).isNotNull();
		assertThat(loaded.getUpdatedAt()).isNotNull();
	}

	@Test
	void optimisticLockingVersionIncrementsOnUpdate() {
		VocabularyItem item = em.persistFlushFind(new VocabularyItem(userId, "de", "Katze", "katze"));
		long before = item.getVersion();

		item.setTranslation("cat");
		em.flush();

		assertThat(item.getVersion()).isEqualTo(before + 1);
	}

	@Test
	void duplicateLemmaIsRejectedByTheDatabase() {
		em.persistAndFlush(new VocabularyItem(userId, "de", "Haus", "haus"));
		assertThatThrownBy(() -> em.persistAndFlush(new VocabularyItem(userId, "de", "das Haus", "haus")))
			.rootCause()
			.hasMessageContaining("vocabulary_items_lemma_uq");
	}

	@Test
	void grammarTopicRecordsStatusHistoryAndAiExplanation() {
		GrammarTopic topic = new GrammarTopic(userId, "de", "Dativ", "dativ", KnowledgeStatus.LEARNING, NOW);
		topic.setCefrLevel(CefrLevel.A2);
		topic.setAiExplanation(new GrammarExplanation("Indirect object case",
				List.of("mit, nach, bei, ... take the dative"), List.of("Ich helfe dem Mann.")), NOW);
		topic.changeStatusByUser(KnowledgeStatus.LEARNED, false, NOW.plusSeconds(60));
		topic.changeStatusByUser(KnowledgeStatus.LEARNED, false, NOW.plusSeconds(120));

		UUID id = em.persistFlushFind(topic).getId();
		em.clear();
		GrammarTopic loaded = em.find(GrammarTopic.class, id);

		assertThat(loaded.getStatus()).isEqualTo(KnowledgeStatus.LEARNED);
		assertThat(loaded.getAiExplanation().summary()).isEqualTo("Indirect object case");
		assertThat(loaded.getAiExplanation().examples()).containsExactly("Ich helfe dem Mann.");
		assertThat(loaded.getStatusHistory()).extracting(c -> c.getReason())
			.containsExactly(StatusChangeReason.CREATED, StatusChangeReason.MANUAL);
		assertThat(loaded.getStatusHistory().get(1).getFromStatus()).isEqualTo(KnowledgeStatus.LEARNING);
	}

	@Test
	void grammarTopicCannotStartAsMastered() {
		assertThatThrownBy(() -> new GrammarTopic(userId, "de", "Akkusativ", "akkusativ", KnowledgeStatus.MASTERED,
				NOW))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void studySessionWithAttachedMaterial() {
		StudySession session = new StudySession(userId, "de", "Lesson 5", LocalDate.of(2026, 11, 9));
		session.setActive(true);
		session = em.persistFlushFind(session);
		VocabularyItem word = em.persist(new VocabularyItem(userId, "de", "Baum", "baum"));
		GrammarTopic topic = em
			.persist(new GrammarTopic(userId, "de", "Perfekt", "perfekt", KnowledgeStatus.NEW, NOW));
		em.persist(new SessionVocabulary(session.getId(), word.getId(), NOW));
		em.persist(new SessionGrammar(session.getId(), topic.getId(), NOW));
		em.flush();
		em.clear();

		assertThat(em.find(StudySession.class, session.getId()).isActive()).isTrue();
		assertThat(em.find(SessionVocabulary.class, new SessionVocabulary.Key(session.getId(), word.getId())))
			.isNotNull();
		assertThat(em.find(SessionGrammar.class, new SessionGrammar.Key(session.getId(), topic.getId())))
			.isNotNull();
	}

}
