package com.linguaflow.exercise;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.exercise.Evaluation.EvaluatedBy;
import com.linguaflow.testsupport.TestUsers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The AI judge (EX-04) against the real model: one wrong translation, graded with its
 * errors mapped to the learner's topics. Off by default because it costs money (about a
 * cent); run it with {@code AI_API_KEY} in the environment:
 * {@code ./mvnw test -Dtest=AnswerCheckerLiveTests -Dai.live=true}.
 */
@EnabledIfSystemProperty(named = "ai.live", matches = "true")
@SpringBootTest(properties = { "linguaflow.ai.provider=anthropic", "linguaflow.ai.api-key=${AI_API_KEY}" })
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AnswerCheckerLiveTests {

	@Autowired
	private AnswerChecker checker;

	@Autowired
	private ExerciseSetRepository sets;

	@Autowired
	private ExerciseRepository exercises;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void gradesAWrongCaseAsADativError() {
		UUID anna = TestUsers.insertLearner(jdbc);
		UUID dativ = UUID.randomUUID();
		jdbc.update("insert into grammar_topics (id, user_id, language_code, title, normalized_title, description, "
				+ "cefr_level) values (?, ?, 'de', 'Dativ', 'dativ', 'Indirect objects and dative prepositions', 'A2')",
				dativ, anna);
		jdbc.update("insert into grammar_topics (id, user_id, language_code, title, normalized_title, cefr_level) "
				+ "values (?, ?, 'de', 'Perfekt', 'perfekt', 'A2')", UUID.randomUUID(), anna);
		ExerciseSet set = sets.saveAndFlush(ExerciseSetRepositoryTests.newSet(anna, 1));
		UUID id = exercises.saveAndFlush(new Exercise(set, 0, new ExerciseContent(ExerciseType.TRANSLATION,
				new ExercisePrompt.Translation(null, CardDirection.NATIVE_TO_TARGET,
						"I help my brother with his homework."),
				new ExerciseSolution.TextAnswer("Ich helfe meinem Bruder bei seinen Hausaufgaben.",
						List.of("Ich helfe meinem Bruder bei den Hausaufgaben."), null)),
				null, List.of(dativ), List.of(), null))
			.getId();

		Evaluation evaluation = checker.check(anna, id,
				new ExerciseAnswer.Text("Ich helfe meinen Bruder bei seine Hausaufgaben."));

		System.out.println("Live verdict: " + evaluation);
		assertThat(evaluation.evaluatedBy()).isEqualTo(EvaluatedBy.AI);
		assertThat(evaluation.correct()).isFalse();
		assertThat(evaluation.score()).isBetween(0.1, 0.95);
		assertThat(evaluation.feedback().errors()).isNotEmpty()
			.anySatisfy(error -> assertThat(error.grammarTopicId()).isEqualTo(dativ));
		assertThat(evaluation.feedback().correctedAnswer()).contains("meinem Bruder");
	}

}
