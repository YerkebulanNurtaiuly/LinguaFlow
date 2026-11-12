package com.linguaflow.exercise;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.vocabulary.KnownWord;
import org.junit.jupiter.api.Test;

import static com.linguaflow.common.domain.ExerciseType.FILL_BLANK;
import static com.linguaflow.common.domain.ExerciseType.MULTIPLE_CHOICE;
import static com.linguaflow.common.domain.ExerciseType.WORD_ORDER;
import static org.assertj.core.api.Assertions.assertThat;

class ExerciseGeneratorTests {

	@Test
	void theCountIsSplitAcrossTypesWithTheFirstTakingTheRest() {
		assertThat(quotas(List.of(FILL_BLANK, MULTIPLE_CHOICE, WORD_ORDER), 10))
			.containsExactly(Map.entry(FILL_BLANK, 4), Map.entry(MULTIPLE_CHOICE, 3), Map.entry(WORD_ORDER, 3));
		assertThat(quotas(List.of(FILL_BLANK, MULTIPLE_CHOICE, WORD_ORDER), 2))
			.containsExactly(Map.entry(FILL_BLANK, 1), Map.entry(MULTIPLE_CHOICE, 1));
		assertThat(quotas(List.of(), 7)).hasSize(ExerciseType.values().length)
			.allSatisfy((type, quota) -> assertThat(quota).isEqualTo(1));
	}

	@Test
	void typesAreInterleaved() {
		assertThat(ExerciseGenerator.interleave(List.of(List.of("a1", "a2", "a3"), List.of("b1"), List.of("c1", "c2"))))
			.containsExactly("a1", "b1", "c1", "a2", "c2", "a3");
		assertThat(ExerciseGenerator.interleave(List.<List<String>>of(List.of(), List.of()))).isEmpty();
	}

	@Test
	void practiceWordsAreGroupedByLessonOnlyWhenTheyComeFromSeveral() {
		KnownWord hund = word("der Hund");
		KnownWord katze = word("die Katze");
		KnownWord kind = word("das Kind");
		KnownWord neu = word("neu");
		List<KnownWord> words = List.of(hund, katze, kind, neu);
		assertThat(ExerciseGenerator.byLesson(words,
				Map.of(hund.id(), "Lektion 2 (2026-11-02)", kind.id(), "Lektion 2 (2026-11-02)", katze.id(),
						"Lektion 5 (2026-11-09)")))
			.containsExactly("Lektion 2 (2026-11-02): der Hund, das Kind", "Lektion 5 (2026-11-09): die Katze");
		// One lesson, or none: nothing to mix
		assertThat(ExerciseGenerator.byLesson(words, Map.of(hund.id(), "Lektion 2", katze.id(), "Lektion 2"))).isEmpty();
		assertThat(ExerciseGenerator.byLesson(words, Map.of())).isEmpty();
	}

	private static KnownWord word(String lemma) {
		return new KnownWord(UUID.randomUUID(), lemma, lemma.toLowerCase(), "x", KnowledgeStatus.LEARNING);
	}

	private static Map<ExerciseType, Integer> quotas(List<ExerciseType> types, int count) {
		return ExerciseGenerator.quotas(new ExerciseSetRequest(List.of(), types, count, null));
	}

}
