package com.linguaflow.common.persistence;

import java.util.List;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.common.config.ClockConfig;
import com.linguaflow.common.config.JpaAuditingConfig;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.grammar.GrammarTopic;
import com.linguaflow.learning.StudySession;
import com.linguaflow.vocabulary.AiStatus;
import com.linguaflow.vocabulary.PartOfSpeech;
import com.linguaflow.vocabulary.VocabularyItem;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dev seed (db/dev) loaded with the same Flyway locations as the dev profile: it must
 * apply on a fresh database, be loadable through the JPA entities, and be safe to rerun.
 */
@DataJpaTest(properties = { "spring.flyway.locations=classpath:db/migration,classpath:db/dev",
		"spring.jpa.hibernate.ddl-auto=validate" })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, JpaAuditingConfig.class, ClockConfig.class })
@ActiveProfiles("test")
class DevSeedTests {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TestEntityManager em;

	@Autowired
	private Flyway flyway;

	@Test
	void seedsADemoLearnerWithGermanMaterial() {
		assertThat(count("select count(*) from users where email = 'demo@linguaflow.local'")).isEqualTo(1);
		assertThat(count("select count(*) from user_languages where active and language_code = 'de'")).isEqualTo(1);
		assertThat(count("select count(*) from vocabulary_items")).isBetween(45, 60);
		assertThat(count("select count(*) from grammar_topics")).isEqualTo(10);
		assertThat(count("select count(*) from study_sessions where active")).isEqualTo(1);
		assertThat(count("select count(*) from session_vocabulary")).isPositive();
		assertThat(count("select count(*) from session_grammar")).isPositive();
	}

	@Test
	void demoPasswordMatchesTheDocumentedValue() {
		String hash = jdbc.queryForObject("select password_hash from users where email = 'demo@linguaflow.local'",
				String.class);
		assertThat(PasswordEncoderFactories.createDelegatingPasswordEncoder().matches("demo-password-123", hash))
			.isTrue();
	}

	@Test
	void seedDataLoadsThroughTheEntities() {
		List<VocabularyItem> words = em.getEntityManager()
			.createQuery("select v from VocabularyItem v", VocabularyItem.class)
			.getResultList();
		assertThat(words).allSatisfy(word -> {
			assertThat(word.getAiStatus()).isEqualTo(AiStatus.READY);
			assertThat(word.getTranslation()).isNotBlank();
		});

		VocabularyItem hund = words.stream().filter(w -> w.getNormalizedLemma().equals("hund")).findFirst().orElseThrow();
		assertThat(hund.getPartOfSpeech()).isEqualTo(PartOfSpeech.NOUN);
		assertThat(hund.getPlural()).isEqualTo("die Hunde");
		assertThat(hund.getExamples()).hasSize(2);
		assertThat(hund.getTags()).extracting(t -> t.getName()).containsExactly("Tiere");

		VocabularyItem gehen = words.stream().filter(w -> w.getNormalizedLemma().equals("gehen")).findFirst().orElseThrow();
		assertThat(gehen.getExtra()).containsEntry("partizip2", "gegangen").containsEntry("auxiliary", "sein");

		assertThat(words).anySatisfy(w -> assertThat(w.getNormalizedLemma()).isEqualTo("strasse"));

		List<GrammarTopic> topics = em.getEntityManager()
			.createQuery("select g from GrammarTopic g", GrammarTopic.class)
			.getResultList();
		assertThat(topics).allSatisfy(t -> assertThat(t.getStatusHistory()).isNotEmpty());
		assertThat(topics).extracting(GrammarTopic::getStatus).contains(KnowledgeStatus.NEW, KnowledgeStatus.LEARNING);

		assertThat(em.getEntityManager().createQuery("select s from StudySession s", StudySession.class).getResultList())
			.hasSize(2);
	}

	@Test
	void rerunningTheSeedIsHarmless() {
		int words = count("select count(*) from vocabulary_items");
		jdbc.update("update vocabulary_items set translation = 'edited by me' where normalized_lemma = 'hund'");

		// Force Flyway to treat the repeatable seed as changed and run it again
		jdbc.update("update flyway_schema_history set checksum = 0 where script = 'R__dev_seed.sql'");
		flyway.migrate();

		assertThat(count("select count(*) from vocabulary_items")).isEqualTo(words);
		assertThat(jdbc.queryForObject("select translation from vocabulary_items where normalized_lemma = 'hund'",
				String.class))
			.isEqualTo("edited by me");
	}

	private int count(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}

}
