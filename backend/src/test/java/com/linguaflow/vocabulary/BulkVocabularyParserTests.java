package com.linguaflow.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.vocabulary.BulkVocabularyParser.Format;
import com.linguaflow.vocabulary.BulkVocabularyParser.ParsedLine;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BulkVocabularyParserTests {

	private static List<ParsedLine> text(String input) {
		return BulkVocabularyParser.parse(input, Format.TEXT);
	}

	private static List<ParsedLine> csv(String input) {
		return BulkVocabularyParser.parse(input, Format.CSV);
	}

	@Nested
	class Text {

		@ParameterizedTest
		@CsvSource(delimiter = '|', value = { "der Hund - dog|der Hund|dog", "der Hund – dog|der Hund|dog",
				"der Hund—dog|der Hund|dog", "der Hund = dog|der Hund|dog", "der Hund\tdog|der Hund|dog",
				"  die   Katze  -  the cat |die Katze|the cat", "die E-Mail|die E-Mail|",
				"die E-Mail - email|die E-Mail|email", "laufen - to run - to walk|laufen|to run - to walk",
				"Guten Morgen!|Guten Morgen!|" })
		void splitsWordAndTranslation(String line, String lemma, String translation) {
			ParsedLine parsed = text(line).getFirst();
			assertThat(parsed.error()).isNull();
			assertThat(parsed.lemma()).isEqualTo(lemma);
			assertThat(parsed.translation()).isEqualTo(translation);
		}

		@Test
		void skipsBlankLinesButKeepsTheirLineNumbers() {
			List<ParsedLine> lines = text("der Hund\r\n\r\n   \ndie Katze\n");
			assertThat(lines).extracting(ParsedLine::lineNumber).containsExactly(1, 4);
			assertThat(lines).extracting(ParsedLine::lemma).containsExactly("der Hund", "die Katze");
		}

		@Test
		void flagsLinesWithoutAWordOrWithOverlongParts() {
			List<ParsedLine> lines = text(" - dog\n" + "x".repeat(201) + "\nHund - " + "y".repeat(501));
			assertThat(lines).extracting(ParsedLine::error)
				.containsExactly("has no word", "word is longer than 200 characters",
						"translation is longer than 500 characters");
		}

		@Test
		void capsTheNumberOfLines() {
			String ok = "Wort\n".repeat(BulkVocabularyParser.MAX_LINES) + "\n\n";
			assertThat(text(ok)).hasSize(BulkVocabularyParser.MAX_LINES);
			assertThatThrownBy(() -> text(ok + "eins mehr"))
				.isInstanceOf(BulkVocabularyParser.TooManyLinesException.class);
		}

	}

	@Nested
	class Csv {

		@Test
		void readsLemmaAndTranslationWithoutAHeader() {
			List<ParsedLine> lines = csv("der Hund,dog\n\"die Katze, klein\",\"the \"\"cat\"\"\"\nlaufen");
			assertThat(lines).extracting(ParsedLine::lemma).containsExactly("der Hund", "die Katze, klein", "laufen");
			assertThat(lines).extracting(ParsedLine::translation).containsExactly("dog", "the \"cat\"", null);
		}

		@Test
		void detectsSemicolonsAndAByteOrderMark() {
			List<ParsedLine> lines = csv("﻿der Hund;dog, hound\n");
			assertThat(lines.getFirst().lemma()).isEqualTo("der Hund");
			assertThat(lines.getFirst().translation()).isEqualTo("dog, hound");
		}

		@Test
		void mapsColumnsByHeaderInAnyOrder() {
			List<ParsedLine> lines = csv("""
					Translation,Word,POS,Level,Notes,Source
					dog,der Hund,noun,a1,"barks,
					loudly",book
					to run,laufen,verb,B1,,
					""");
			assertThat(lines).hasSize(2);
			ParsedLine dog = lines.getFirst();
			assertThat(dog.lineNumber()).isEqualTo(2);
			assertThat(dog.lemma()).isEqualTo("der Hund");
			assertThat(dog.partOfSpeech()).isEqualTo(PartOfSpeech.NOUN);
			assertThat(dog.cefrLevel()).isEqualTo(CefrLevel.A1);
			assertThat(dog.notes()).isEqualTo("barks,\nloudly");
			// The quoted newline counts, so the next record starts on line 4
			assertThat(lines.get(1).lineNumber()).isEqualTo(4);
			assertThat(lines.get(1).partOfSpeech()).isEqualTo(PartOfSpeech.VERB);
		}

		@Test
		void flagsUnknownValuesAndExtraColumns() {
			assertThat(csv("lemma,pos\nHund,thing").getFirst().error()).isEqualTo("unknown part of speech 'thing'");
			assertThat(csv("lemma,level\nHund,Z9").getFirst().error()).isEqualTo("unknown level 'Z9'");
			assertThat(csv("Hund,dog,noun").getFirst().error()).startsWith("has 3 columns");
		}

	}

}
