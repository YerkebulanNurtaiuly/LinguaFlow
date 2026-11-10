package com.linguaflow.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

import java.util.Optional;
import java.util.UUID;

import com.linguaflow.TestcontainersConfiguration;
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
 * Two requests create or rename to the same tag name at once: the one that loses past the
 * name check hits {@code tags_name_uq}, which must become the same 409 with the winner's
 * ID. Forced by letting the first name lookup miss (see VocabularyDuplicateRaceTests).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class TagNameRaceTests {

	@MockitoSpyBean
	private TagRepository tagRepository;

	@Autowired
	private TagService tags;

	@Autowired
	private UserRepository users;

	@Autowired
	private JdbcTemplate jdbc;

	@AfterEach
	void tearDown() {
		users.deleteAll();
	}

	@Test
	void reportsACreateCaughtByTheUniqueIndex() {
		UUID anna = TestUsers.insertLearner(jdbc);
		UUID winner = tags.create(anna, "Food").tag().getId();
		doReturn(Optional.empty()).doReturn(Optional.of(winner))
			.when(tagRepository)
			.findIdByName(eq(anna), eq("de"), eq("food"));

		assertThatThrownBy(() -> tags.create(anna, "food"))
			.isInstanceOfSatisfying(TagNameTakenException.class, ex -> assertThat(ex.existingId()).isEqualTo(winner));
		assertThat(tagRepository.countByUserId(anna)).isOne();
	}

	@Test
	void reportsARenameCaughtByTheUniqueIndex() {
		UUID anna = TestUsers.insertLearner(jdbc);
		UUID winner = tags.create(anna, "Food").tag().getId();
		UUID drinks = tags.create(anna, "Drinks").tag().getId();
		doReturn(Optional.empty()).doReturn(Optional.of(winner))
			.when(tagRepository)
			.findIdByName(eq(anna), eq("de"), eq("FOOD"));

		assertThatThrownBy(() -> tags.rename(anna, drinks, "FOOD"))
			.isInstanceOfSatisfying(TagNameTakenException.class, ex -> assertThat(ex.existingId()).isEqualTo(winner));
		assertThat(tagRepository.findByIdAndUserId(drinks, anna)).get()
			.extracting(Tag::getName)
			.isEqualTo("Drinks");
	}

}
