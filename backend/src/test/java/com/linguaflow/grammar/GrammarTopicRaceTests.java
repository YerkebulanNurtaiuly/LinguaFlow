package com.linguaflow.grammar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

import java.util.Optional;
import java.util.UUID;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.grammar.GrammarTopicService.GrammarTopicChanges;
import com.linguaflow.grammar.GrammarTopicService.NewGrammarTopic;
import com.linguaflow.testsupport.TestUsers;
import com.linguaflow.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Two requests take the same title at once: the one that passes the check too late hits
 * {@code grammar_topics_title_uq}, which must become the same 409 with the winner's ID.
 * Forced by letting the first lookup miss (see VocabularyDuplicateRaceTests).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class GrammarTopicRaceTests {

	@MockitoSpyBean
	private GrammarTopicRepository repository;

	@Autowired
	private GrammarTopicService topics;

	@Autowired
	private UserRepository users;

	@Autowired
	private JdbcTemplate jdbc;

	@AfterEach
	void tearDown() {
		users.deleteAll();
	}

	@Test
	void reportsACreateCaughtByTheUniqueConstraint() {
		UUID anna = TestUsers.insertLearner(jdbc);
		UUID winner = topics.create(anna, NewGrammarTopic.of("Dativ")).getId();
		doReturn(Optional.empty()).doReturn(Optional.of(winner))
			.when(repository)
			.findDuplicateId(eq(anna), eq("de"), eq("dativ"));

		assertThatThrownBy(() -> topics.create(anna, NewGrammarTopic.of("dativ")))
			.isInstanceOfSatisfying(DuplicateGrammarTopicException.class,
					ex -> assertThat(ex.existingId()).isEqualTo(winner));
		assertThat(repository.countByUserId(anna)).isOne();
	}

	@Test
	void reportsARenameCaughtByTheUniqueConstraint() {
		UUID anna = TestUsers.insertLearner(jdbc);
		UUID winner = topics.create(anna, NewGrammarTopic.of("Dativ")).getId();
		UUID other = topics.create(anna, NewGrammarTopic.of("Akkusativ")).getId();
		doReturn(Optional.empty()).doReturn(Optional.of(winner))
			.when(repository)
			.findDuplicateId(eq(anna), eq("de"), eq("dativ"));

		assertThatThrownBy(
				() -> topics.update(anna, other, new GrammarTopicChanges("DATIV", null, null, null, null)))
			.isInstanceOfSatisfying(DuplicateGrammarTopicException.class,
					ex -> assertThat(ex.existingId()).isEqualTo(winner));
		assertThat(topics.get(anna, other).getTitle()).isEqualTo("Akkusativ");
	}

}
