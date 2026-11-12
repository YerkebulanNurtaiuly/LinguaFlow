package com.linguaflow.common.persistence;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.exercise.ExerciseDifficulty;
import com.linguaflow.exercise.ExerciseSetSource;
import com.linguaflow.exercise.ExerciseSetStatus;
import com.linguaflow.flashcard.ReviewGrade;
import com.linguaflow.learning.practice.PracticeMode;
import com.linguaflow.flashcard.SrsState;
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
		assertThat(Arrays.stream(applied).map(m -> m.getVersion().getVersion())).contains("1", "2", "3", "4");
		assertThat(flyway.info().pending()).isEmpty();
		assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
	}

	@Test
	void demoDataIsNotLoadedOutsideTheDevProfile() {
		assertThat(jdbc.queryForObject("select count(*) from users where email = 'demo@linguaflow.local'",
				Integer.class))
			.isZero();
		assertThat(Arrays.stream(flyway.info().applied()).map(m -> m.getScript())).doesNotContain("R__dev_seed.sql");
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

	// ---- V3: practice, progress, AI ------------------------------------------------------------

	@Test
	void requiredQueryIndexesExist() {
		assertThat(indexDefinition("srs_state_due_ix")).contains("(user_id, due_at)");
		assertThat(indexDefinition("mistakes_topic_ix")).contains("(user_id, grammar_topic_id)");
		assertThat(indexDefinition("ai_jobs_queue_ix")).contains("(status, next_run_at)");
	}

	@Test
	void exerciseTypeEnumMatchesTheDatabaseCheck() {
		String check = jdbc.queryForObject(
				"select pg_get_constraintdef(oid) from pg_constraint where conname = 'exercises_type_ck'", String.class);
		for (ExerciseType type : ExerciseType.values()) {
			assertThat(check).as("exercises_type_ck").contains("'" + type.name() + "'");
		}
		assertThat(check.split("'", -1).length / 2).as("number of values").isEqualTo(ExerciseType.values().length);
	}

	@Test
	void cardDirectionEnumMatchesTheDatabaseChecks() {
		for (String constraint : List.of("flashcards_direction_ck", "user_languages_card_directions_ck")) {
			String check = jdbc.queryForObject(
					"select pg_get_constraintdef(oid) from pg_constraint where conname = ?", String.class, constraint);
			for (CardDirection direction : CardDirection.values()) {
				assertThat(check).as(constraint).contains("'" + direction.name() + "'");
			}
		}
	}

	@Test
	void reviewGradesAndSrsStatesMatchTheDatabaseChecks() {
		String grades = jdbc.queryForObject(
				"select pg_get_constraintdef(oid) from pg_constraint where conname = 'flashcard_reviews_grade_ck'",
				String.class);
		for (ReviewGrade grade : ReviewGrade.values()) {
			assertThat(grades).contains("'" + grade.name() + "'");
		}
		String states = jdbc.queryForObject(
				"select pg_get_constraintdef(oid) from pg_constraint where conname = 'srs_state_state_ck'", String.class);
		for (SrsState.State state : SrsState.State.values()) {
			assertThat(states).contains("'" + state.name() + "'");
		}
	}

	@Test
	void exerciseEnumsMatchTheDatabaseChecks() {
		assertCheckMatches("exercises_difficulty_ck", ExerciseDifficulty.values());
		assertCheckMatches("exercise_sets_source_ck", ExerciseSetSource.values());
		assertCheckMatches("exercise_sets_status_ck", ExerciseSetStatus.values());
	}

	@Test
	void practiceModesMatchTheDatabaseCheck() {
		assertCheckMatches("practice_sessions_mode_ck", PracticeMode.values());
	}

	private void assertCheckMatches(String constraint, Enum<?>[] values) {
		String check = jdbc.queryForObject(
				"select pg_get_constraintdef(oid) from pg_constraint where conname = ?", String.class, constraint);
		for (Enum<?> value : values) {
			assertThat(check).as(constraint).contains("'" + value.name() + "'");
		}
		assertThat(check.split("'", -1).length / 2).as(constraint + " values").isEqualTo(values.length);
	}

	// ---- V6: card directions -------------------------------------------------------------------

	@Test
	void learnersReviewBothCardDirectionsByDefault() {
		UUID user = learner("cards.de");
		assertThat(jdbc.queryForObject(
				"select array_to_string(card_directions, ',') from user_languages where user_id = ?", String.class, user))
			.isEqualTo("TARGET_TO_NATIVE,NATIVE_TO_TARGET");
	}

	@Test
	void atLeastOneKnownCardDirectionIsRequired() {
		UUID user = learner("nocards.de");
		assertThatThrownBy(() -> jdbc.update("update user_languages set card_directions = '{}' where user_id = ?", user))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("user_languages_card_directions_ck");
	}

	@Test
	void unknownCardDirectionsAreRejected() {
		UUID user = learner("badcards.de");
		assertThatThrownBy(
				() -> jdbc.update("update user_languages set card_directions = '{SIDEWAYS}' where user_id = ?", user))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("user_languages_card_directions_ck");
	}

	// ---- V7: the state a card was graded in ----------------------------------------------------

	@Test
	void reviewStateBeforeMatchesTheSrsStates() {
		String states = jdbc.queryForObject(
				"select pg_get_constraintdef(oid) from pg_constraint where conname = 'flashcard_reviews_state_before_ck'",
				String.class);
		for (SrsState.State state : SrsState.State.values()) {
			assertThat(states).contains("'" + state.name() + "'");
		}
	}

	@Test
	void reviewStateBeforeIsRequired() {
		UUID user = learner("statebefore@example.de");
		UUID card = insertFlashcard(user, insertVocabulary(user, "bett", "NOUN"));
		assertThatThrownBy(() -> jdbc.update(
				"insert into flashcard_reviews (id, flashcard_id, user_id, review_token, grade, reviewed_at, "
						+ "interval_before, interval_after, ease_before, ease_after) "
						+ "values (?, ?, ?, ?, 'GOOD', now(), 0, 1, 2.5, 2.5)",
				UUID.randomUUID(), card, user, UUID.randomUUID()))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("state_before");
	}

	@Test
	void flashcardMustBelongToTheOwnerOfItsWord() {
		UUID owner = learner("owner@example.de");
		UUID other = learner("other@example.de");
		UUID item = insertVocabulary(owner, "tisch", "NOUN");
		assertThatThrownBy(() -> insertFlashcard(other, item)).isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("flashcards_item_fk");
	}

	@Test
	void reviewSubmissionIsIdempotentPerToken() {
		UUID user = learner("review@example.de");
		UUID card = insertFlashcard(user, insertVocabulary(user, "stuhl", "NOUN"));
		UUID token = UUID.randomUUID();
		insertReview(user, card, token);
		assertThatThrownBy(() -> insertReview(user, card, token)).isInstanceOf(DuplicateKeyException.class)
			.hasMessageContaining("flashcard_reviews_token_uq");
	}

	@Test
	void easeFactorCannotDropBelowTheSm2Floor() {
		UUID user = learner("ease@example.de");
		UUID card = insertFlashcard(user, insertVocabulary(user, "lampe", "NOUN"));
		assertThatThrownBy(() -> jdbc.update("insert into srs_state (flashcard_id, user_id, ease_factor) values (?, ?, 1.2)",
				card, user))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("srs_state_ease_ck");
	}

	@Test
	void scheduledCardsNeedADueDate() {
		UUID user = learner("due@example.de");
		UUID card = insertFlashcard(user, insertVocabulary(user, "tür", "NOUN"));
		assertThatThrownBy(() -> jdbc.update(
				"insert into srs_state (flashcard_id, user_id, state) values (?, ?, 'REVIEW')", card, user))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("srs_state_due_ck");
	}

	@Test
	void deletingAWordRemovesItsCardsButKeepsMistakeHistory() {
		UUID user = learner("history@example.de");
		UUID item = insertVocabulary(user, "fenster", "NOUN");
		UUID card = insertFlashcard(user, item);
		jdbc.update("insert into srs_state (flashcard_id, user_id) values (?, ?)", card, user);
		insertReview(user, card, UUID.randomUUID());
		UUID exercise = insertExercise(user);
		jdbc.update("insert into exercise_vocabulary values (?, ?)", exercise, item);
		insertMistake(user, insertAttempt(user, exercise), null, item);

		jdbc.update("delete from vocabulary_items where id = ?", item);

		assertThat(count("flashcards", user)).isZero();
		assertThat(count("srs_state", user)).isZero();
		assertThat(count("flashcard_reviews", user)).isZero();
		assertThat(count("exercises", user)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from exercise_vocabulary", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from mistakes where user_id = ? and vocabulary_item_id is null",
				Integer.class, user))
			.isEqualTo(1);
	}

	@Test
	void deletingAGrammarTopicKeepsExercisesAndMistakes() {
		UUID user = learner("topic@example.de");
		UUID topic = UUID.randomUUID();
		jdbc.update("insert into grammar_topics (id, user_id, language_code, title, normalized_title) "
				+ "values (?, ?, 'de', 'Dativ', 'dativ')", topic, user);
		UUID exercise = insertExercise(user);
		jdbc.update("insert into exercise_grammar_topics values (?, ?)", exercise, topic);
		jdbc.update("insert into grammar_topic_progress (grammar_topic_id, user_id) values (?, ?)", topic, user);
		insertMistake(user, insertAttempt(user, exercise), topic, null);

		jdbc.update("delete from grammar_topics where id = ?", topic);

		assertThat(count("exercises", user)).isEqualTo(1);
		assertThat(count("exercise_attempts", user)).isEqualTo(1);
		assertThat(count("grammar_topic_progress", user)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from mistakes where user_id = ? and grammar_topic_id is null",
				Integer.class, user))
			.isEqualTo(1);
	}

	@Test
	void practiceItemReferencesExactlyOneMatchingItem() {
		UUID user = learner("items@example.de");
		UUID session = insertPracticeSession(user);
		UUID exercise = insertExercise(user);
		assertThatThrownBy(() -> jdbc.update("insert into practice_session_items "
				+ "(practice_session_id, position, item_type, exercise_id) values (?, 0, 'FLASHCARD', ?)", session,
				exercise))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("practice_session_items_type_ck");
	}

	@Test
	void completedPracticeSessionNeedsCompletionTime() {
		UUID user = learner("complete@example.de");
		assertThatThrownBy(() -> jdbc.update("insert into practice_sessions "
				+ "(id, user_id, language_code, mode, config, status, started_at) "
				+ "values (?, ?, 'de', 'MIXED', '{}', 'COMPLETED', now())", UUID.randomUUID(), user))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("practice_sessions_completed_ck");
	}

	@Test
	void deletingAUserRemovesAllTheirPracticeAndAiData() {
		UUID user = learner("gdpr@example.de");
		UUID card = insertFlashcard(user, insertVocabulary(user, "buch", "NOUN"));
		jdbc.update("insert into srs_state (flashcard_id, user_id) values (?, ?)", card, user);
		insertReview(user, card, UUID.randomUUID());
		UUID session = insertPracticeSession(user);
		jdbc.update("insert into practice_session_items (practice_session_id, position, item_type, flashcard_id) "
				+ "values (?, 0, 'FLASHCARD', ?)", session, card);
		UUID exercise = insertExercise(user);
		insertMistake(user, insertAttempt(user, exercise), null, null);
		UUID job = UUID.randomUUID();
		jdbc.update("insert into ai_jobs (id, user_id, type, payload) values (?, ?, 'VOCABULARY_ENRICHMENT', '{}')", job,
				user);
		jdbc.update("insert into ai_requests (id, user_id, job_id, use_case, model, prompt_version, success) "
				+ "values (?, ?, ?, 'enrich', 'test-model', 'v1', true)", UUID.randomUUID(), user, job);

		jdbc.update("delete from users where id = ?", user);

		for (String table : List.of("flashcards", "srs_state", "flashcard_reviews", "practice_sessions", "exercise_sets",
				"exercises", "exercise_attempts", "mistakes", "ai_jobs", "ai_requests")) {
			assertThat(count(table, user)).as(table).isZero();
		}
		assertThat(jdbc.queryForObject("select count(*) from practice_session_items", Integer.class)).isZero();
	}

	private UUID insertFlashcard(UUID user, UUID item) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into flashcards (id, user_id, vocabulary_item_id, direction) "
				+ "values (?, ?, ?, 'TARGET_TO_NATIVE')", id, user, item);
		return id;
	}

	private void insertReview(UUID user, UUID card, UUID token) {
		jdbc.update("insert into flashcard_reviews (id, flashcard_id, user_id, review_token, grade, reviewed_at, "
				+ "interval_before, interval_after, ease_before, ease_after, state_before) "
				+ "values (?, ?, ?, ?, 'GOOD', now(), 0, 1, 2.5, 2.5, 'NEW')", UUID.randomUUID(), card, user, token);
	}

	private UUID insertPracticeSession(UUID user) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into practice_sessions (id, user_id, language_code, mode, config, started_at) "
				+ "values (?, ?, 'de', 'MIXED', '{}', now())", id, user);
		return id;
	}

	private UUID insertExercise(UUID user) {
		UUID set = UUID.randomUUID();
		UUID id = UUID.randomUUID();
		jdbc.update("insert into exercise_sets (id, user_id, language_code, source, request) "
				+ "values (?, ?, 'de', 'MANUAL', '{}')", set, user);
		jdbc.update("insert into exercises (id, exercise_set_id, user_id, position, type, prompt, solution) "
				+ "values (?, ?, ?, 0, 'FILL_BLANK', '{}', '{}')", id, set, user);
		return id;
	}

	private UUID insertAttempt(UUID user, UUID exercise) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into exercise_attempts (id, exercise_id, user_id, answer, correct, score, evaluated_by, "
				+ "attempted_at) values (?, ?, ?, '{}', false, 0, 'RULES', now())", id, exercise, user);
		return id;
	}

	private void insertMistake(UUID user, UUID attempt, UUID grammarTopic, UUID vocabularyItem) {
		jdbc.update("insert into mistakes (id, attempt_id, user_id, grammar_topic_id, vocabulary_item_id, error_type) "
				+ "values (?, ?, ?, ?, ?, 'WRONG_CASE')", UUID.randomUUID(), attempt, user, grammarTopic,
				vocabularyItem);
	}

	private String indexDefinition(String name) {
		return jdbc.queryForObject("select indexdef from pg_indexes where indexname = ?", String.class, name);
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
