package com.linguaflow.common.persistence;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.linguaflow.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the full Flyway chain against a fresh PostgreSQL and checks that the constraints
 * from docs/database/schema.md hold. Each test runs in a rolled-back transaction.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class MigrationTests {

	@Autowired
	private Flyway flyway;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void allMigrationsAreAppliedAndValid() {
		MigrationInfo[] applied = flyway.info().applied();
		assertThat(applied).isNotEmpty().allMatch(m -> m.getState() == MigrationState.SUCCESS);
		assertThat(Arrays.stream(applied).map(m -> m.getVersion().getVersion())).contains("1", "2");
		assertThat(flyway.info().pending()).isEmpty();
		assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
	}

	@Test
	void seedsGermanAndEnglish() {
		List<String> codes = jdbc.queryForList("select code from languages order by code", String.class);
		assertThat(codes).containsExactly("de", "en");
		assertThat(jdbc.queryForObject("select supported_as_target from languages where code = 'de'", Boolean.class))
			.isTrue();
	}

	@Test
	void emailIsUniqueIgnoringCase() {
		insertUser("Anna@Example.de");
		assertThatThrownBy(() -> insertUser("anna@example.de")).isInstanceOf(DuplicateKeyException.class)
			.hasMessageContaining("users_email_lower_uq");
	}

	@Test
	void appliesUserDefaults() {
		UUID id = insertUser("defaults@example.de");
		assertThat(jdbc.queryForMap("select role, enabled, timezone, interface_language_code from users where id = ?",
				id))
			.containsEntry("role", "USER")
			.containsEntry("enabled", true)
			.containsEntry("timezone", "UTC")
			.containsEntry("interface_language_code", "en");
	}

	@Test
	void rejectsUnknownRole() {
		assertThatThrownBy(() -> jdbc.update(
				"insert into users (id, email, password_hash, display_name, role) values (?, ?, 'x', 'X', 'GOD')",
				UUID.randomUUID(), "role@example.de"))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("users_role_ck");
	}

	@Test
	void allowsOnlyOneActiveLanguagePerUser() {
		UUID user = insertUser("active@example.de");
		insertUserLanguage(user, "de", "en", true);
		assertThatThrownBy(() -> insertUserLanguage(user, "en", "de", true))
			.isInstanceOf(DuplicateKeyException.class)
			.hasMessageContaining("user_languages_one_active_uq");
	}

	@Test
	void allowsSeveralInactiveLanguages() {
		UUID user = insertUser("inactive@example.de");
		insertUserLanguage(user, "de", "en", true);
		insertUserLanguage(user, "en", "de", false);
		assertThat(count("user_languages", user)).isEqualTo(2);
	}

	@Test
	void explanationLanguageMustDifferFromTarget() {
		UUID user = insertUser("same@example.de");
		assertThatThrownBy(() -> insertUserLanguage(user, "de", "de", false))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("user_languages_explanation_differs_ck");
	}

	@Test
	void rejectsUnknownLanguage() {
		UUID user = insertUser("unknown@example.de");
		assertThatThrownBy(() -> insertUserLanguage(user, "xx", "en", false))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("user_languages_language_fk");
	}

	@Test
	void appliesLearningSettingDefaults() {
		UUID user = insertUser("settings@example.de");
		insertUserLanguage(user, "de", "en", true);
		assertThat(jdbc.queryForMap(
				"select daily_review_goal, new_cards_per_day, practice_session_length, cardinality(preferred_exercise_types) as types "
						+ "from user_languages where user_id = ?",
				user))
			.containsEntry("daily_review_goal", 50)
			.containsEntry("new_cards_per_day", 20)
			.containsEntry("practice_session_length", 20)
			.containsEntry("types", 0);
	}

	@Test
	void deletingUserCascadesToTheirData() {
		UUID user = insertUser("delete@example.de");
		insertUserLanguage(user, "de", "en", true);
		jdbc.update("insert into refresh_tokens (id, user_id, token_hash, family_id, expires_at) "
				+ "values (?, ?, 'hash-1', ?, now() + interval '7 days')", UUID.randomUUID(), user, UUID.randomUUID());

		jdbc.update("delete from users where id = ?", user);

		assertThat(count("user_languages", user)).isZero();
		assertThat(count("refresh_tokens", user)).isZero();
	}

	@Test
	void refreshTokenHashIsUnique() {
		UUID user = insertUser("tokens@example.de");
		String sql = "insert into refresh_tokens (id, user_id, token_hash, family_id, expires_at) "
				+ "values (?, ?, 'same-hash', ?, now() + interval '7 days')";
		jdbc.update(sql, UUID.randomUUID(), user, UUID.randomUUID());
		assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), user, UUID.randomUUID()))
			.isInstanceOf(DuplicateKeyException.class);
	}

	// ---- V2: learning material ------------------------------------------------------------

	@Test
	void sameLemmaWithUnknownPartOfSpeechIsADuplicate() {
		UUID user = learner("dup@example.de");
		insertVocabulary(user, "hund", null);
		assertThatThrownBy(() -> insertVocabulary(user, "hund", null)).isInstanceOf(DuplicateKeyException.class)
			.hasMessageContaining("vocabulary_items_lemma_uq");
	}

	@Test
	void sameLemmaWithDifferentPartOfSpeechIsAllowed() {
		UUID user = learner("essen@example.de");
		insertVocabulary(user, "essen", "NOUN");
		insertVocabulary(user, "essen", "VERB");
		assertThat(count("vocabulary_items", user)).isEqualTo(2);
	}

	@Test
	void vocabularyRequiresTheUserToLearnThatLanguage() {
		UUID user = insertUser("nolang@example.de");
		assertThatThrownBy(() -> insertVocabulary(user, "hund", null))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("vocabulary_items_user_language_fk");
	}

	@Test
	void tagNamesAreUniqueIgnoringCase() {
		UUID user = learner("tags@example.de");
		String sql = "insert into tags (id, user_id, language_code, name) values (?, ?, 'de', ?)";
		jdbc.update(sql, UUID.randomUUID(), user, "Food");
		assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), user, "food"))
			.isInstanceOf(DuplicateKeyException.class)
			.hasMessageContaining("tags_name_uq");
	}

	@Test
	void onlyOneActiveStudySessionPerLanguage() {
		UUID user = learner("sessions@example.de");
		String sql = "insert into study_sessions (id, user_id, language_code, title, studied_on, active) "
				+ "values (?, ?, 'de', ?, current_date, ?)";
		jdbc.update(sql, UUID.randomUUID(), user, "Lesson 1", false);
		jdbc.update(sql, UUID.randomUUID(), user, "Lesson 2", true);
		assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), user, "Lesson 3", true))
			.isInstanceOf(DuplicateKeyException.class)
			.hasMessageContaining("study_sessions_one_active_uq");
	}

	@Test
	void deletingVocabularyRemovesItsLinksButKeepsTheSession() {
		UUID user = learner("links@example.de");
		UUID item = insertVocabulary(user, "katze", "NOUN");
		UUID tag = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		jdbc.update("insert into tags (id, user_id, language_code, name) values (?, ?, 'de', 'Tiere')", tag, user);
		jdbc.update("insert into vocabulary_item_tags values (?, ?)", item, tag);
		jdbc.update("insert into study_sessions (id, user_id, language_code, title, studied_on) "
				+ "values (?, ?, 'de', 'Lesson 1', current_date)", session, user);
		jdbc.update("insert into session_vocabulary (study_session_id, vocabulary_item_id) values (?, ?)", session,
				item);

		jdbc.update("delete from vocabulary_items where id = ?", item);

		assertThat(jdbc.queryForObject("select count(*) from vocabulary_item_tags", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from session_vocabulary", Integer.class)).isZero();
		assertThat(count("study_sessions", user)).isEqualTo(1);
		assertThat(count("tags", user)).isEqualTo(1);
	}

	@Test
	void droppingALanguageRemovesItsLearningMaterial() {
		UUID user = learner("drop@example.de");
		insertVocabulary(user, "baum", "NOUN");
		jdbc.update("insert into grammar_topics (id, user_id, language_code, title, normalized_title) "
				+ "values (?, ?, 'de', 'Dativ', 'dativ')", UUID.randomUUID(), user);

		jdbc.update("delete from user_languages where user_id = ?", user);

		assertThat(count("vocabulary_items", user)).isZero();
		assertThat(count("grammar_topics", user)).isZero();
	}

	@Test
	void catalogSlugMustBeKebabCase() {
		assertThatThrownBy(() -> jdbc.update(
				"insert into grammar_topic_catalog (id, language_code, slug, title, description, cefr_level) "
						+ "values (?, 'de', 'Not A Slug', 'x', 'x', 'A1')",
				UUID.randomUUID()))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("grammar_topic_catalog_slug_format_ck");
	}

	private UUID learner(String email) {
		UUID user = insertUser(email);
		insertUserLanguage(user, "de", "en", true);
		return user;
	}

	private UUID insertVocabulary(UUID user, String normalizedLemma, String partOfSpeech) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma, part_of_speech) "
				+ "values (?, ?, 'de', ?, ?, ?)", id, user, normalizedLemma, normalizedLemma, partOfSpeech);
		return id;
	}

	private UUID insertUser(String email) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into users (id, email, password_hash, display_name) values (?, ?, 'hash', 'Test')", id,
				email);
		return id;
	}

	private void insertUserLanguage(UUID user, String target, String explanation, boolean active) {
		jdbc.update("insert into user_languages (user_id, language_code, explanation_language_code, active) "
				+ "values (?, ?, ?, ?)", user, target, explanation, active);
	}

	private int count(String table, UUID user) {
		return jdbc.queryForObject("select count(*) from " + table + " where user_id = ?", Integer.class, user);
	}

}
