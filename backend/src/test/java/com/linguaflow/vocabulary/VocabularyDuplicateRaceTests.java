package com.linguaflow.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

import java.util.Optional;
import java.util.UUID;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.testsupport.TestUsers;
import com.linguaflow.user.UserRepository;
import com.linguaflow.vocabulary.VocabularyCommands.NewVocabularyItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The race the service-level check can't see: another request inserts the same word
 * between our duplicate check and our insert. The unique constraint must turn that into
 * the same 409 with the winner's ID. Forced deterministically by letting the first
 * duplicate lookup miss.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class VocabularyDuplicateRaceTests {

	@MockitoSpyBean
	private VocabularyItemRepository items;

	@Autowired
	private VocabularyService vocabulary;

	@Autowired
	private UserRepository users;

	@Autowired
	private JdbcTemplate jdbc;

	@AfterEach
	void tearDown() {
		users.deleteAll();
	}

	@Test
	void reportsADuplicateCaughtByTheUniqueConstraint() {
		UUID anna = TestUsers.insertLearner(jdbc);
		UUID winner = vocabulary.create(anna, NewVocabularyItem.of("der Hund")).getId();
		// The pre-check runs "before" the winner's insert committed, so it finds nothing;
		// the lookup after the constraint fires finds the winner. (A Spring Data proxy has
		// no real method for Mockito to call, so both answers are stubbed; the query itself
		// is covered by VocabularyServiceTests.)
		doReturn(Optional.empty()).doReturn(Optional.of(winner))
			.when(items)
			.findDuplicateId(eq(anna), eq("de"), eq("hund"), any());

		assertThatThrownBy(() -> vocabulary.create(anna, NewVocabularyItem.of("Hund")))
			.isInstanceOfSatisfying(DuplicateVocabularyItemException.class,
					ex -> assertThat(ex.existingId()).isEqualTo(winner));
		assertThat(items.countByUserId(anna)).isOne();
	}

}
