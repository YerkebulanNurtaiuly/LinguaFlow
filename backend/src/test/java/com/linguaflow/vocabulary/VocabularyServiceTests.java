package com.linguaflow.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ErrorCode;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.common.error.NotFoundException;
import com.linguaflow.testsupport.TestUsers;
import com.linguaflow.user.UserRepository;
import com.linguaflow.vocabulary.VocabularyCommands.NewExample;
import com.linguaflow.vocabulary.VocabularyCommands.NewVocabularyItem;
import com.linguaflow.vocabulary.VocabularyCommands.VocabularyItemChanges;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Vocabulary business rules against a real PostgreSQL (VOC-01). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class VocabularyServiceTests {

	@Autowired
	private VocabularyService vocabulary;

	@Autowired
	private VocabularyItemRepository items;

	@Autowired
	private UserRepository users;

	@Autowired
	private JdbcTemplate jdbc;

	private UUID anna;

	private UUID ben;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		anna = TestUsers.insertLearner(jdbc);
		ben = TestUsers.insertLearner(jdbc);
	}

	@AfterEach
	void tearDown() {
		users.deleteAll();
	}

	private static NewVocabularyItem word(String lemma, PartOfSpeech partOfSpeech) {
		return new NewVocabularyItem(lemma, partOfSpeech, null, null, null, null, null, null, List.of());
	}

	@Nested
	class Create {

		@Test
		void storesTheWordInTheActiveLanguageWithItsNormalizedLemma() {
			VocabularyItem item = vocabulary.create(anna,
					new NewVocabularyItem("  der Hund ", PartOfSpeech.NOUN, " dog ", Gender.MASCULINE, "die Hunde",
							CefrLevel.A1, null, "", List.of(new NewExample("Der Hund bellt.", "The dog barks."),
									new NewExample("Ich habe einen Hund.", null))));

			VocabularyItem stored = vocabulary.get(anna, item.getId());
			assertThat(stored.getLanguageCode()).isEqualTo("de");
			assertThat(stored.getLemma()).isEqualTo("der Hund");
			assertThat(stored.getNormalizedLemma()).isEqualTo("hund");
			assertThat(stored.getTranslation()).isEqualTo("dog");
			assertThat(stored.getNotes()).isNull();
			assertThat(stored.getStatus()).isEqualTo(KnowledgeStatus.LEARNING);
			assertThat(stored.getAiStatus()).isEqualTo(AiStatus.PENDING_AI);
			assertThat(stored.getExamples()).extracting(VocabularyExample::getSentence)
				.containsExactly("Der Hund bellt.", "Ich habe einen Hund.");
		}

		@Test
		void rejectsTheSameWordTypedDifferently() {
			UUID existing = vocabulary.create(anna, NewVocabularyItem.of("der Hund")).getId();

			for (String variant : List.of("Hund", "DER HUND", "  der  hund ")) {
				assertThatThrownBy(() -> vocabulary.create(anna, NewVocabularyItem.of(variant)))
					.isInstanceOfSatisfying(DuplicateVocabularyItemException.class, ex -> {
						assertThat(ex.existingId()).isEqualTo(existing);
						assertThat(ex.code()).isEqualTo(ErrorCode.VOCABULARY_DUPLICATE);
						assertThat(ex.properties()).containsEntry("existingId", existing);
					});
			}
			assertThat(items.countByUserId(anna)).isOne();
		}

		@Test
		void treatsSharpSAndDoubleSAsTheSameWord() {
			vocabulary.create(anna, NewVocabularyItem.of("die Straße"));

			assertThatThrownBy(() -> vocabulary.create(anna, NewVocabularyItem.of("Strasse")))
				.isInstanceOf(DuplicateVocabularyItemException.class);
		}

		@Test
		void allowsTheSameLemmaAsADifferentPartOfSpeech() {
			vocabulary.create(anna, word("laufen", PartOfSpeech.VERB));
			vocabulary.create(anna, word("das Laufen", PartOfSpeech.NOUN));

			assertThatThrownBy(() -> vocabulary.create(anna, word("Laufen", PartOfSpeech.VERB)))
				.isInstanceOf(DuplicateVocabularyItemException.class);
			assertThat(items.countByUserId(anna)).isEqualTo(2);
		}

		@Test
		void aWordWithoutPartOfSpeechOnlyClashesWithAnotherOneWithout() {
			vocabulary.create(anna, NewVocabularyItem.of("Bank"));
			vocabulary.create(anna, word("die Bank", PartOfSpeech.NOUN));

			assertThatThrownBy(() -> vocabulary.create(anna, NewVocabularyItem.of("bank")))
				.isInstanceOf(DuplicateVocabularyItemException.class);
		}

		@Test
		void letsEveryUserHaveTheirOwnCopyOfAWord() {
			vocabulary.create(anna, NewVocabularyItem.of("der Hund"));
			vocabulary.create(ben, NewVocabularyItem.of("der Hund"));

			assertThat(items.countByUserId(anna)).isOne();
			assertThat(items.countByUserId(ben)).isOne();
		}

		@Test
		void reportsAConcurrentDuplicateWithTheWinnersId() throws Exception {
			int threads = 8;
			CountDownLatch start = new CountDownLatch(1);
			ExecutorService pool = Executors.newFixedThreadPool(threads);
			try {
				List<Future<UUID>> results = new ArrayList<>();
				for (int i = 0; i < threads; i++) {
					Callable<UUID> task = () -> {
						start.await();
						return vocabulary.create(anna, NewVocabularyItem.of("der Apfel")).getId();
					};
					results.add(pool.submit(task));
				}
				start.countDown();

				List<UUID> created = new ArrayList<>();
				List<UUID> reportedExisting = new ArrayList<>();
				for (Future<UUID> result : results) {
					try {
						created.add(result.get());
					}
					catch (java.util.concurrent.ExecutionException ex) {
						assertThat(ex.getCause()).isInstanceOf(DuplicateVocabularyItemException.class);
						reportedExisting.add(((DuplicateVocabularyItemException) ex.getCause()).existingId());
					}
				}
				assertThat(created).hasSize(1);
				assertThat(reportedExisting).hasSize(threads - 1).containsOnly(created.getFirst());
			}
			finally {
				pool.shutdownNow();
			}
		}

		@Test
		void requiresALanguageFirst() {
			jdbc.update("delete from user_languages where user_id = ?", anna);

			assertThatThrownBy(() -> vocabulary.create(anna, NewVocabularyItem.of("Hund")))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.code()).isEqualTo(ErrorCode.NO_ACTIVE_LANGUAGE));
		}

		@Test
		void validatesTheInput() {
			assertInvalid(() -> vocabulary.create(anna, NewVocabularyItem.of("   ")), "lemma");
			assertInvalid(() -> vocabulary.create(anna, NewVocabularyItem.of("x".repeat(201))), "lemma");
			assertInvalid(() -> vocabulary.create(anna, new NewVocabularyItem("Hund", null, null, null, null, null,
					KnowledgeStatus.MASTERED, null, List.of())), "status");
			assertInvalid(() -> vocabulary.create(anna,
					new NewVocabularyItem("laufen", PartOfSpeech.VERB, null, Gender.NEUTER, null, null, null, null,
							List.of())),
					"gender");
			assertInvalid(() -> vocabulary.create(anna, new NewVocabularyItem("Hund", null, null, null, null, null,
					null, null, List.of(new NewExample(" ", null)))), "examples[0].sentence");
			assertThat(items.countByUserId(anna)).isZero();
		}

		@Test
		void acceptsTheLongestAllowedLemma() {
			String longest = "x".repeat(VocabularyService.MAX_LEMMA_LENGTH);
			assertThat(vocabulary.create(anna, NewVocabularyItem.of(longest)).getLemma()).hasSize(200);
		}

	}

	@Nested
	class Update {

		@Test
		void renamesAndRenormalizes() {
			UUID id = vocabulary.create(anna, NewVocabularyItem.of("der Hnud")).getId();

			VocabularyItem updated = vocabulary.update(anna, id,
					new VocabularyItemChanges("der Hund", null, null, null, null, null, null, null));

			assertThat(updated.getLemma()).isEqualTo("der Hund");
			assertThat(updated.getNormalizedLemma()).isEqualTo("hund");
		}

		@Test
		void allowsRetypingTheItemsOwnLemma() {
			UUID id = vocabulary.create(anna, NewVocabularyItem.of("der Hund")).getId();

			VocabularyItem updated = vocabulary.update(anna, id,
					new VocabularyItemChanges("Der Hund", null, null, null, null, null, null, null));

			assertThat(updated.getLemma()).isEqualTo("Der Hund");
		}

		@Test
		void rejectsARenameOntoAnotherWord() {
			UUID dog = vocabulary.create(anna, NewVocabularyItem.of("der Hund")).getId();
			UUID cat = vocabulary.create(anna, NewVocabularyItem.of("die Katze")).getId();

			assertThatThrownBy(() -> vocabulary.update(anna, cat,
					new VocabularyItemChanges("Hund", null, null, null, null, null, null, null)))
				.isInstanceOfSatisfying(DuplicateVocabularyItemException.class,
						ex -> assertThat(ex.existingId()).isEqualTo(dog));
			assertThat(vocabulary.get(anna, cat).getLemma()).isEqualTo("die Katze");
		}

		@Test
		void userEditsStopCountingAsAiGenerated() {
			UUID id = vocabulary.create(anna, word("der Hund", PartOfSpeech.NOUN)).getId();
			jdbc.update("update vocabulary_items set translation = 'hound', "
					+ "ai_generated_fields = '{translation,gender,plural}' where id = ?", id);

			VocabularyItem updated = vocabulary.update(anna, id,
					new VocabularyItemChanges(null, null, "dog", null, null, null, null, null));

			assertThat(updated.getTranslation()).isEqualTo("dog");
			assertThat(updated.getAiGeneratedFields()).containsExactlyInAnyOrder("gender", "plural");
		}

		@Test
		void clearsTheGenderWhenTheWordIsNoLongerANoun() {
			UUID id = vocabulary.create(anna,
					new NewVocabularyItem("Essen", PartOfSpeech.NOUN, null, Gender.NEUTER, null, null, null, null,
							List.of()))
				.getId();

			VocabularyItem updated = vocabulary.update(anna, id,
					new VocabularyItemChanges(null, PartOfSpeech.VERB, null, null, null, null, null, null));

			assertThat(updated.getPartOfSpeech()).isEqualTo(PartOfSpeech.VERB);
			assertThat(updated.getGender()).isNull();
		}

		@Test
		void neverLetsTheLearnerSetMastered() {
			UUID id = vocabulary.create(anna, NewVocabularyItem.of("Hund")).getId();

			assertInvalid(() -> vocabulary.update(anna, id,
					new VocabularyItemChanges(null, null, null, null, null, null, KnowledgeStatus.MASTERED, null)),
					"status");
			assertThat(vocabulary.get(anna, id).getStatus()).isEqualTo(KnowledgeStatus.LEARNING);
		}

		@Test
		void leavesAbsentFieldsUnchanged() {
			UUID id = vocabulary.create(anna, new NewVocabularyItem("der Hund", PartOfSpeech.NOUN, "dog",
					Gender.MASCULINE, "die Hunde", CefrLevel.A1, KnowledgeStatus.NEW, "cute", List.of()))
				.getId();

			VocabularyItem updated = vocabulary.update(anna, id,
					new VocabularyItemChanges(null, null, null, null, null, CefrLevel.A2, null, null));

			assertThat(updated.getCefrLevel()).isEqualTo(CefrLevel.A2);
			assertThat(updated.getTranslation()).isEqualTo("dog");
			assertThat(updated.getGender()).isEqualTo(Gender.MASCULINE);
			assertThat(updated.getStatus()).isEqualTo(KnowledgeStatus.NEW);
			assertThat(updated.getNotes()).isEqualTo("cute");
		}

	}

	@Nested
	class Ownership {

		@Test
		void hidesOtherUsersWordsAsNotFound() {
			UUID annasWord = vocabulary.create(anna, NewVocabularyItem.of("der Hund")).getId();
			VocabularyItemChanges rename = new VocabularyItemChanges("Katze", null, null, null, null, null, null,
					null);

			assertThatThrownBy(() -> vocabulary.get(ben, annasWord)).isInstanceOf(NotFoundException.class);
			assertThatThrownBy(() -> vocabulary.update(ben, annasWord, rename)).isInstanceOf(NotFoundException.class);
			assertThatThrownBy(() -> vocabulary.delete(ben, annasWord)).isInstanceOf(NotFoundException.class);
			assertThat(vocabulary.get(anna, annasWord).getLemma()).isEqualTo("der Hund");
		}

		@Test
		void deletesOwnWords() {
			UUID id = vocabulary.create(anna, NewVocabularyItem.of("der Hund")).getId();

			vocabulary.delete(anna, id);

			assertThat(items.countByUserId(anna)).isZero();
			assertThatThrownBy(() -> vocabulary.delete(anna, id)).isInstanceOf(NotFoundException.class);
		}

		@Test
		void listsOnlyTheUsersWordsNewestFirst() {
			vocabulary.create(anna, NewVocabularyItem.of("der Hund"));
			vocabulary.create(anna, NewVocabularyItem.of("die Katze"));
			vocabulary.create(ben, NewVocabularyItem.of("das Pferd"));

			var page = vocabulary.list(anna, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));

			assertThat(page.getContent()).extracting(VocabularyItem::getLemma)
				.containsExactlyInAnyOrder("der Hund", "die Katze");
			assertThat(page.getTotalElements()).isEqualTo(2);
		}

	}

	private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
		assertThatThrownBy(call).isInstanceOfSatisfying(InvalidFieldException.class,
				ex -> assertThat(ex.field()).isEqualTo(field));
	}

}
