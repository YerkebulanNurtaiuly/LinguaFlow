package com.linguaflow.exercise;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.exercise.ExercisePrompt.FillBlank;
import com.linguaflow.exercise.ExerciseSolution.Blank;
import com.linguaflow.exercise.ExerciseSolution.Blanks;
import com.linguaflow.testsupport.OwnedRepositoryTest;
import com.linguaflow.testsupport.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trips exercise sets and exercises through Hibernate in validate mode: typed JSON,
 * enums, the link tables and what deleting linked material does (EX-01).
 */
@OwnedRepositoryTest
class ExerciseMappingTests {

	@Autowired
	private TestEntityManager em;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ExerciseSetRepository sets;

	@Autowired
	private ExerciseRepository exercises;

	private UUID userId;

	@BeforeEach
	void createLearner() {
		userId = TestUsers.insertLearner(jdbc);
	}

	@Test
	void everyTypeRoundTripsWithItsLinks() {
		UUID topic = insertTopic("Dativ");
		UUID word = insertWord("Hund");
		ExerciseSet set = sets.saveAndFlush(ExerciseSetRepositoryTests.newSet(userId, 7));
		ExerciseType[] types = ExerciseType.values();
		for (int i = 0; i < types.length; i++) {
			exercises.save(new Exercise(set, types.length - 1 - i, ExerciseSamples.of(types[i]),
					ExerciseDifficulty.MEDIUM, List.of(topic), List.of(word), null));
		}
		em.flush();
		em.clear();

		List<Exercise> loaded = exercises.findAllByUserIdAndExerciseSetIdOrderByPosition(userId, set.getId());
		assertThat(loaded).extracting(Exercise::getPosition)
			.containsExactly((short) 0, (short) 1, (short) 2, (short) 3, (short) 4, (short) 5, (short) 6);
		for (Exercise exercise : loaded) {
			assertThat(exercise.content()).isEqualTo(ExerciseSamples.of(exercise.getType()));
			assertThat(exercise.getUserId()).isEqualTo(userId);
			assertThat(exercise.getDifficulty()).isEqualTo(ExerciseDifficulty.MEDIUM);
			assertThat(exercise.getGrammarTopicIds()).containsExactly(topic);
			assertThat(exercise.getVocabularyItemIds()).containsExactly(word);
			assertThat(exercise.isReported()).isFalse();
			assertThat(exercise.getCreatedAt()).isNotNull();
		}
		assertThat(Arrays.stream(types).map(t -> loaded.get(types.length - 1 - t.ordinal()).getType()))
			.containsExactly(types);
		// Another user sees none of them
		assertThat(exercises.findAllByUserIdAndExerciseSetIdOrderByPosition(TestUsers.insertLearner(jdbc),
				set.getId()))
			.isEmpty();
	}

	@Test
	void theStoredJsonIsTheRecordShape() {
		ExerciseSet set = sets.saveAndFlush(ExerciseSetRepositoryTests.newSet(userId, 1));
		Exercise exercise = exercises.saveAndFlush(new Exercise(set, 0, ExerciseSamples.of(ExerciseType.FILL_BLANK),
				null, List.of(), List.of(), null));

		assertThat(jdbc.queryForObject("select prompt ->> 'text' from exercises where id = ?", String.class,
				exercise.getId()))
			.isEqualTo("Ich gebe ___ Mann ___ Buch.");
		assertThat(jdbc.queryForObject("select solution -> 'blanks' -> 0 ->> 'answer' from exercises where id = ?",
				String.class, exercise.getId()))
			.isEqualTo("dem");
		assertThat(jdbc.queryForObject("select request ->> 'count' from exercise_sets where id = ?", String.class,
				set.getId()))
			.isEqualTo("1");
	}

	@Test
	void deletingMaterialRemovesTheLinkButKeepsTheExercise() {
		UUID topic = insertTopic("Dativ");
		UUID word = insertWord("Hund");
		ExerciseSet set = sets.saveAndFlush(ExerciseSetRepositoryTests.newSet(userId, 1));
		Exercise exercise = exercises.saveAndFlush(new Exercise(set, 0, ExerciseSamples.of(ExerciseType.TRANSLATION),
				null, List.of(topic), List.of(word), null));

		jdbc.update("delete from grammar_topics where id = ?", topic);
		jdbc.update("delete from vocabulary_items where id = ?", word);
		em.clear();

		Exercise reloaded = exercises.requireOwned(exercise.getId(), userId);
		assertThat(reloaded.getGrammarTopicIds()).isEmpty();
		assertThat(reloaded.getVocabularyItemIds()).isEmpty();
	}

	@Test
	void deletingTheSetDeletesItsExercises() {
		ExerciseSet set = sets.saveAndFlush(ExerciseSetRepositoryTests.newSet(userId, 1));
		Exercise exercise = exercises.saveAndFlush(
				new Exercise(set, 0, ExerciseSamples.of(ExerciseType.WORD_ORDER), null, List.of(), List.of(), null));

		assertThat(sets.deleteByIdAndUserId(set.getId(), userId)).isEqualTo(1);
		em.flush();
		em.clear();
		assertThat(exercises.existsByIdAndUserId(exercise.getId(), userId)).isFalse();
	}

	@Test
	void setsMoveFromGeneratingToReadyOrFailedOnce() {
		ExerciseSet set = sets.saveAndFlush(ExerciseSetRepositoryTests.newSet(userId, 10));
		assertThat(set.getStatus()).isEqualTo(ExerciseSetStatus.GENERATING);
		set.markReady();
		sets.saveAndFlush(set);
		em.clear();

		ExerciseSet reloaded = sets.requireOwned(set.getId(), userId);
		assertThat(reloaded.getStatus()).isEqualTo(ExerciseSetStatus.READY);
		assertThat(reloaded.getRequest()).isEqualTo(new ExerciseSetRequest(List.of(),
				List.of(ExerciseType.FILL_BLANK), 10, ExerciseDifficulty.EASY));
		assertThatThrownBy(reloaded::markFailed).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void theWholeRequestSurvivesTheDatabase() {
		ExerciseSetRequest request = new ExerciseSetRequest(List.of(UUID.randomUUID(), UUID.randomUUID()),
				List.of(ExerciseType.WORD_ORDER), 5, null, List.of(UUID.randomUUID()), true);
		ExerciseSet set = sets.saveAndFlush(
				new ExerciseSet(userId, "de", ExerciseSetSource.PRACTICE, request, null));
		em.clear();
		assertThat(sets.requireOwned(set.getId(), userId).getRequest()).isEqualTo(request);
	}

	@Test
	void reportingKeepsTheFirstTimeAndTheLatestReason() {
		ExerciseSet set = sets.saveAndFlush(ExerciseSetRepositoryTests.newSet(userId, 1));
		Exercise exercise = exercises.saveAndFlush(new Exercise(set, 0,
				ExerciseSamples.of(ExerciseType.MULTIPLE_CHOICE), null, List.of(), List.of(), null));
		Instant first = Instant.parse("2026-11-12T08:00:00Z");

		exercise.report("  Two options are correct. ", first);
		exercise.report("Actually the question is unclear.", first.plusSeconds(60));
		exercises.saveAndFlush(exercise);
		em.clear();

		Exercise reloaded = exercises.requireOwned(exercise.getId(), userId);
		assertThat(reloaded.isReported()).isTrue();
		assertThat(reloaded.getReportedAt()).isEqualTo(first);
		assertThat(reloaded.getReportReason()).isEqualTo("Actually the question is unclear.");
	}

	@Test
	void inconsistentContentIsRefused() {
		ExerciseSet set = sets.saveAndFlush(ExerciseSetRepositoryTests.newSet(userId, 1));
		ExerciseContent broken = new ExerciseContent(ExerciseType.FILL_BLANK, new FillBlank(null, "Kein Platz.", null),
				new Blanks(List.of(new Blank("dem", List.of())), null));

		assertThatThrownBy(() -> new Exercise(set, 0, broken, null, List.of(), List.of(), null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("prompt.text: must contain at least one gap");
		assertThatThrownBy(() -> new Exercise(ExerciseSetRepositoryTests.newSet(userId, 1), 0,
				ExerciseSamples.of(ExerciseType.TRANSLATION), null, List.of(), List.of(), null))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("saved first");
	}

	private UUID insertTopic(String title) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into grammar_topics (id, user_id, language_code, title, normalized_title) "
				+ "values (?, ?, 'de', ?, ?)", id, userId, title, title.toLowerCase());
		return id;
	}

	private UUID insertWord(String lemma) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma) "
				+ "values (?, ?, 'de', ?, ?)", id, userId, lemma, lemma.toLowerCase());
		return id;
	}

}
