package com.linguaflow.flashcard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.vocabulary.Auxiliary;
import com.linguaflow.vocabulary.Gender;
import com.linguaflow.vocabulary.LemmaNormalizer;
import com.linguaflow.vocabulary.PartOfSpeech;
import com.linguaflow.vocabulary.VocabularyItem;
import org.junit.jupiter.api.Test;

class FlashcardContentTests {

	private static VocabularyItem word(String lemma) {
		return new VocabularyItem(UUID.randomUUID(), "de", lemma, LemmaNormalizer.normalize("de", lemma));
	}

	private static VocabularyItem hund(String typed, String plural) {
		VocabularyItem hund = word(typed);
		hund.setPartOfSpeech(PartOfSpeech.NOUN);
		hund.setGender(Gender.MASCULINE);
		hund.setPlural(plural);
		hund.setTranslation("dog");
		hund.addExample("Der Hund bellt.", "The dog barks.", true);
		return hund;
	}

	@Test
	void asksForTheMeaningOfTheWordWithItsArticle() {
		FlashcardContent card = FlashcardContent.of(CardDirection.TARGET_TO_NATIVE, hund("Hund", "Hunde"));

		assertThat(card.front()).isEqualTo("der Hund");
		assertThat(card.hint()).isNull();
		assertThat(card.back()).isEqualTo("dog");
		assertThat(card.plural()).isEqualTo("die Hunde");
		assertThat(card.examples()).containsExactly(new FlashcardContent.Example("Der Hund bellt.", "The dog barks."));
		assertThat(card.complete()).isTrue();
	}

	@Test
	void asksForTheWordFromItsMeaningWithAPartOfSpeechHint() {
		// However the learner typed it, the article comes from the gender
		FlashcardContent card = FlashcardContent.of(CardDirection.NATIVE_TO_TARGET, hund("der Hund", "die Hunde"));

		assertThat(card.front()).isEqualTo("dog");
		assertThat(card.hint()).isEqualTo("noun");
		assertThat(card.back()).isEqualTo("der Hund");
		assertThat(card.plural()).isEqualTo("die Hunde");
	}

	@Test
	void showsAVerbsPrincipalParts() {
		VocabularyItem laufen = word("laufen");
		laufen.setPartOfSpeech(PartOfSpeech.VERB);
		laufen.setTranslation("to run");
		laufen.setVerbForms("lief", "gelaufen", Auxiliary.SEIN);
		VocabularyItem machen = word("machen");
		machen.setPartOfSpeech(PartOfSpeech.VERB);
		machen.setTranslation("to do");
		machen.setVerbForms("machte", "gemacht", Auxiliary.HABEN);

		assertThat(FlashcardContent.of(CardDirection.TARGET_TO_NATIVE, laufen).verbForms())
			.isEqualTo(new FlashcardContent.VerbForms("lief", "ist gelaufen"));
		assertThat(FlashcardContent.of(CardDirection.NATIVE_TO_TARGET, machen).verbForms().perfect())
			.isEqualTo("hat gemacht");
		assertThat(FlashcardContent.of(CardDirection.TARGET_TO_NATIVE, laufen).plural()).isNull();

		// Older data, like the dev seed, writes the auxiliary in lower case
		VocabularyItem gehen = word("gehen");
		gehen.setPartOfSpeech(PartOfSpeech.VERB);
		gehen.setTranslation("to go");
		gehen.setExtra(java.util.Map.of("praeteritum", "ging", "partizip2", "gegangen", "auxiliary", "sein"));
		assertThat(FlashcardContent.of(CardDirection.TARGET_TO_NATIVE, gehen).verbForms().perfect())
			.isEqualTo("ist gegangen");
	}

	@Test
	void aWordWithoutTranslationGivesAnIncompleteCard() {
		VocabularyItem pending = word("Regenschirm");

		FlashcardContent card = FlashcardContent.of(CardDirection.NATIVE_TO_TARGET, pending);

		assertThat(card.complete()).isFalse();
		assertThat(card.front()).isEqualTo(FlashcardContent.NO_TRANSLATION);
		assertThat(card.back()).isEqualTo("Regenschirm");
		assertThat(card.hint()).isNull();
	}

	@Test
	void showsAtMostThreeExamplesAndFollowsEdits() {
		VocabularyItem hund = hund("Hund", "Hunde");
		hund.addExample("Zwei", null, false);
		hund.addExample("Drei", null, false);
		hund.addExample("Vier", null, false);
		assertThat(FlashcardContent.of(CardDirection.TARGET_TO_NATIVE, hund).examples()).hasSize(3);

		// Built on read: an edited word gives the new content at once
		hund.setTranslation("hound");
		assertThat(FlashcardContent.of(CardDirection.TARGET_TO_NATIVE, hund).back()).isEqualTo("hound");
	}

}
