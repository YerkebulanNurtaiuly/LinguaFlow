package com.linguaflow.flashcard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.testsupport.MutableClock;
import com.linguaflow.testsupport.TestUsers;
import com.linguaflow.testsupport.TestUsers.TestUser;
import com.linguaflow.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The review queue and grading (SRS-02). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, ReviewApiTests.ClockConfig.class })
@ActiveProfiles("test")
class ReviewApiTests {

	/** 15:00 in Almaty (UTC+5): Anna's today is 2026-11-11, from 2026-11-10T19:00Z. */
	private static final Instant T0 = Instant.parse("2026-11-11T10:00:00Z");

	private static final Instant START_OF_TODAY = Instant.parse("2026-11-10T19:00:00Z");

	private static final Instant TOMORROW = Instant.parse("2026-11-11T19:00:00Z");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private FlashcardGenerator generator;

	@Autowired
	private ReviewService reviews;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private UserRepository users;

	@Autowired
	private RegistrationService registration;

	@Autowired
	private AccessTokenService accessTokens;

	@Autowired
	private MutableClock clock;

	private TestUser anna;

	private TestUser ben;

	private int words;

	@BeforeEach
	void setUp() {
		clock.set(T0);
		users.deleteAll();
		TestUsers testUsers = new TestUsers(registration, accessTokens, jdbc);
		anna = testUsers.learner("anna@example.de");
		ben = testUsers.learner("ben@example.de");
		jdbc.update("update users set timezone = 'Asia/Almaty' where id = ?", anna.id());
		words = 0;
	}

	/** A READY word with both cards, added {@code words} minutes after the first. */
	private UUID word(TestUser user, String lemma, String translation) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma, translation,
				    ai_status, created_at, updated_at)
				values (?, ?, 'de', ?, ?, ?, 'READY', ?, ?)""", id, user.id(), lemma, lemma.toLowerCase(), translation,
				Timestamp.from(T0.plus(Duration.ofMinutes(words))), Timestamp.from(T0));
		words++;
		generator.createFor(user.id(), id);
		return id;
	}

	private UUID card(UUID itemId, String direction) {
		return jdbc.queryForObject("select id from flashcards where vocabulary_item_id = ? and direction = ?",
				UUID.class, itemId, direction);
	}

	private UUID t2n(UUID itemId) {
		return card(itemId, "TARGET_TO_NATIVE");
	}

	private UUID n2t(UUID itemId) {
		return card(itemId, "NATIVE_TO_TARGET");
	}

	private void schedule(UUID cardId, String state, Instant dueAt, String ease, int lapses) {
		jdbc.update("""
				update srs_state set state = ?, due_at = ?, ease_factor = ?::numeric, lapses = ?,
				    interval_days = case when ? = 'REVIEW' then 5 else 0 end, last_reviewed_at = ?
				where flashcard_id = ?""", state, Timestamp.from(dueAt), ease, lapses, state,
				Timestamp.from(T0.minus(Duration.ofDays(10))), cardId);
	}

	private ResultActions send(TestUser user, MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header("Authorization", user.bearer()));
	}

	private ResultActions grade(TestUser user, UUID cardId, String grade, UUID token) throws Exception {
		return send(user, post("/api/reviews").contentType(MediaType.APPLICATION_JSON)
			.content("{\"flashcardId\":\"%s\",\"grade\":\"%s\",\"reviewToken\":\"%s\",\"durationMs\":4200}"
				.formatted(cardId, grade, token)));
	}

	private List<String> queueIds(TestUser user) throws Exception {
		String json = send(user, get("/api/reviews/due")).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return com.jayway.jsonpath.JsonPath.read(json, "$.cards[*].card.id");
	}

	@Test
	void queuesOverdueThenWeakThenNew() throws Exception {
		UUID overdueOld = t2n(word(anna, "Haus", "house"));
		UUID overdueNew = t2n(word(anna, "Baum", "tree"));
		UUID weak = t2n(word(anna, "Brot", "bread"));
		UUID strong = t2n(word(anna, "Tisch", "table"));
		UUID learning = t2n(word(anna, "Stuhl", "chair"));
		UUID laterToday = t2n(word(anna, "Lampe", "lamp"));
		UUID tomorrow = t2n(word(anna, "Fenster", "window"));
		UUID fresh = word(anna, "Hund", "dog");
		for (UUID scheduled : List.of(overdueOld, overdueNew, weak, strong, learning, laterToday, tomorrow)) {
			// Their reverse cards are reviewed too, but not due yet
			schedule(card(jdbc.queryForObject("select vocabulary_item_id from flashcards where id = ?", UUID.class,
					scheduled), "NATIVE_TO_TARGET"), "REVIEW", T0.plus(Duration.ofDays(30)), "2.50", 0);
		}
		schedule(overdueOld, "REVIEW", START_OF_TODAY.minus(Duration.ofDays(3)), "2.50", 0);
		schedule(overdueNew, "REVIEW", START_OF_TODAY.minus(Duration.ofDays(1)), "2.50", 0);
		schedule(weak, "REVIEW", START_OF_TODAY, "1.30", 4);
		schedule(strong, "REVIEW", START_OF_TODAY, "2.80", 0);
		schedule(learning, "RELEARNING", T0.minus(Duration.ofMinutes(2)), "2.30", 1);
		schedule(laterToday, "LEARNING", T0.plus(Duration.ofMinutes(8)), "2.50", 0);
		schedule(tomorrow, "REVIEW", TOMORROW, "2.50", 0);

		assertThat(queueIds(anna)).containsExactly(overdueOld.toString(), overdueNew.toString(),
				learning.toString(), weak.toString(), strong.toString(), t2n(fresh).toString());

		send(anna, get("/api/reviews/due")).andExpect(jsonPath("$.dueCount").value(5))
			.andExpect(jsonPath("$.newCount").value(1))
			.andExpect(jsonPath("$.today").value("2026-11-11"))
			.andExpect(jsonPath("$.reviewedToday").value(0))
			.andExpect(jsonPath("$.dailyReviewGoal").value(50))
			.andExpect(jsonPath("$.newCardsPerDay").value(20))
			.andExpect(jsonPath("$.nextLearningDueAt").value(T0.plus(Duration.ofMinutes(8)).toString()))
			.andExpect(jsonPath("$.cards[5].card.content.front").value("Hund"))
			// A new card's previews: AGAIN 1 minute, GOOD 10 minutes, EASY 4 days from Anna's today
			.andExpect(jsonPath("$.cards[5].previews.again.dueAt").value(T0.plus(Duration.ofMinutes(1)).toString()))
			.andExpect(jsonPath("$.cards[5].previews.good.state").value("LEARNING"))
			.andExpect(jsonPath("$.cards[5].previews.easy.intervalDays").value(4))
			.andExpect(jsonPath("$.cards[5].previews.easy.dueAt")
				.value(START_OF_TODAY.plus(Duration.ofDays(4)).toString()));

		// The limit cuts the queue, not the count
		send(anna, get("/api/reviews/due").param("limit", "2")).andExpect(jsonPath("$.cards", hasSize(2)))
			.andExpect(jsonPath("$.dueCount").value(5));
	}

	@Test
	void respectsTheDailyNewCardLimit() throws Exception {
		UUID hund = word(anna, "Hund", "dog");
		UUID katze = word(anna, "Katze", "cat");
		UUID maus = word(anna, "Maus", "mouse");
		jdbc.update("update user_languages set new_cards_per_day = 2 where user_id = ?", anna.id());

		assertThat(queueIds(anna)).containsExactly(t2n(hund).toString(), t2n(katze).toString());

		grade(anna, t2n(hund), "GOOD", UUID.randomUUID()).andExpect(status().isCreated());
		// Hund's first card is now learning (due in 10 minutes); one new card is left today
		send(anna, get("/api/reviews/due")).andExpect(jsonPath("$.newCardsToday").value(1))
			.andExpect(jsonPath("$.newCount").value(1))
			.andExpect(jsonPath("$.cards[*].card.id", contains(t2n(katze).toString())));

		grade(anna, t2n(katze), "EASY", UUID.randomUUID()).andExpect(status().isCreated());
		send(anna, get("/api/reviews/due")).andExpect(jsonPath("$.newCount").value(0))
			.andExpect(jsonPath("$.cards", hasSize(0)))
			.andExpect(jsonPath("$.reviewedToday").value(2));

		// The next day (through the service: the access token would have expired) the limit
		// starts again; Hund's unfinished step is overdue, and the reverse cards may come now
		clock.set(TOMORROW.plus(Duration.ofHours(1)));
		assertThat(reviews.queue(anna.id(), 50).cards().stream().map(item -> item.view().card().getId()))
			.containsExactly(t2n(hund), n2t(hund), n2t(katze));
		assertThat(maus).isNotNull();
	}

	@Test
	void asksAWordsReverseCardOnlyAfterItsFirstCardWasLearned() throws Exception {
		UUID hund = word(anna, "Hund", "dog");
		assertThat(queueIds(anna)).containsExactly(t2n(hund).toString());

		// Seen before today: the reverse card is introduced
		schedule(t2n(hund), "REVIEW", T0.plus(Duration.ofDays(3)), "2.50", 0);
		assertThat(queueIds(anna)).containsExactly(n2t(hund).toString());

		// When the learner only reviews "native → target", it isn't held back
		UUID katze = word(anna, "Katze", "cat");
		jdbc.update("update user_languages set card_directions = '{NATIVE_TO_TARGET}' where user_id = ?", anna.id());
		assertThat(queueIds(anna)).containsExactly(n2t(hund).toString(), n2t(katze).toString());
	}

	@Test
	void leavesOutSuspendedCardsWordsWithoutTranslationAndOtherLanguagesOrUsers() throws Exception {
		UUID hund = word(anna, "Hund", "dog");
		UUID leer = word(anna, "Regenschirm", "dummy");
		jdbc.update("update vocabulary_items set translation = null where id = ?", leer);
		UUID katze = word(anna, "Katze", "cat");
		jdbc.update("update flashcards set suspended = true where id = ?", t2n(katze));
		word(ben, "Maus", "mouse");

		// Katze's first card is suspended, so its reverse card isn't held back
		assertThat(queueIds(anna)).containsExactly(t2n(hund).toString(), n2t(katze).toString());
		assertThat(queueIds(ben)).hasSize(1);
	}

	@Test
	void gradingReschedulesAndLogsOnceForEachToken() throws Exception {
		UUID hund = t2n(word(anna, "Hund", "dog"));
		schedule(hund, "REVIEW", START_OF_TODAY, "2.50", 0);
		UUID token = UUID.randomUUID();

		grade(anna, hund, "GOOD", token).andExpect(status().isCreated())
			.andExpect(jsonPath("$.replayed").value(false))
			.andExpect(jsonPath("$.review.grade").value("GOOD"))
			.andExpect(jsonPath("$.review.durationMs").value(4200))
			.andExpect(jsonPath("$.review.intervalBefore").value(5))
			.andExpect(jsonPath("$.review.intervalAfter").value(13))
			.andExpect(jsonPath("$.review.easeBefore").value(2.5))
			.andExpect(jsonPath("$.review.reviewedAt").value(T0.toString()))
			.andExpect(jsonPath("$.card.srs.state").value("REVIEW"))
			.andExpect(jsonPath("$.card.srs.intervalDays").value(13))
			.andExpect(jsonPath("$.card.srs.dueAt").value(START_OF_TODAY.plus(Duration.ofDays(13)).toString()));

		// The same token again, even with another grade: nothing changes
		clock.advance(Duration.ofMinutes(1));
		grade(anna, hund, "AGAIN", token).andExpect(status().isOk())
			.andExpect(jsonPath("$.replayed").value(true))
			.andExpect(jsonPath("$.review.grade").value("GOOD"))
			.andExpect(jsonPath("$.card.srs.intervalDays").value(13));
		assertThat(jdbc.queryForObject("select count(*) from flashcard_reviews where flashcard_id = ?", Long.class,
				hund))
			.isEqualTo(1);

		// A new token is a new review
		grade(anna, hund, "AGAIN", UUID.randomUUID()).andExpect(status().isCreated())
			.andExpect(jsonPath("$.card.srs.state").value("RELEARNING"))
			.andExpect(jsonPath("$.card.srs.lapses").value(1));
		assertThat(jdbc.queryForObject("select count(*) from flashcard_reviews where flashcard_id = ?", Long.class,
				hund))
			.isEqualTo(2);
	}

	@Test
	void concurrentCopiesOfASubmissionAreRecordedOnce() throws Exception {
		UUID hund = t2n(word(anna, "Hund", "dog"));
		UUID token = UUID.randomUUID();
		var submission = new ReviewService.Submission(hund, ReviewGrade.GOOD, token, null);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<ReviewService.Result>> results = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
			for (int i = 0; i < 4; i++) {
				Callable<ReviewService.Result> task = () -> {
					start.await();
					return reviews.submit(anna.id(), submission);
				};
				results.add(pool.submit(task));
			}
			start.countDown();
			List<Boolean> replayed = new ArrayList<>();
			for (Future<ReviewService.Result> result : results) {
				replayed.add(result.get().replayed());
			}
			assertThat(replayed).containsOnlyOnce(false);
		}
		assertThat(jdbc.queryForObject("select count(*) from flashcard_reviews where flashcard_id = ?", Long.class,
				hund))
			.isEqualTo(1);
		assertThat(jdbc.queryForObject("select repetitions from srs_state where flashcard_id = ?", Integer.class,
				hund))
			.isEqualTo(1);
	}

	@Test
	void onlyTheOwnerGradesACard() throws Exception {
		UUID hund = t2n(word(anna, "Hund", "dog"));
		grade(ben, hund, "GOOD", UUID.randomUUID()).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("select state from srs_state where flashcard_id = ?", String.class, hund))
			.isEqualTo("NEW");
		grade(anna, hund, "GOOD", UUID.randomUUID()).andExpect(status().isCreated());
	}

	@Test
	void checksItsInput() throws Exception {
		send(anna, get("/api/reviews/due").param("limit", "0")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("limit"));
		send(anna, get("/api/reviews/due").param("limit", "201")).andExpect(status().isBadRequest());
		send(anna, post("/api/reviews").contentType(MediaType.APPLICATION_JSON)
			.content("{\"flashcardId\":\"%s\",\"reviewToken\":\"%s\",\"durationMs\":-1}".formatted(UUID.randomUUID(),
					UUID.randomUUID())))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[*].field",
					org.hamcrest.Matchers.containsInAnyOrder("grade", "durationMs")));

		TestUser newcomer = new TestUsers(registration, accessTokens, jdbc).user("new@example.de");
		send(newcomer, get("/api/reviews/due")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NO_ACTIVE_LANGUAGE"));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockConfig {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(T0);
		}

	}

}
