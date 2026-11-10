package com.linguaflow.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.linguaflow.common.domain.CefrLevel;
import com.linguaflow.common.domain.KnowledgeStatus;
import com.linguaflow.common.error.InvalidFieldException;
import com.linguaflow.vocabulary.VocabularyCsv.ImportRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VocabularyCsvTests {

	@ParameterizedTest
	@ValueSource(strings = { "=SUM(A1)", "+49", "-ung", "@home", "plain", "a, b", "say \"hi\"", " padded ",
			"line\nbreak", "'quoted" })
	void cellsSurviveAWriteReadRoundTrip(String value) {
		String csv = "lemma,notes\r\n" + VocabularyCsv.escape(value) + "," + VocabularyCsv.escape(value) + "\r\n";
		ImportRow row = VocabularyCsv.read(csv, 10).getFirst();
		assertThat(row.notes()).isEqualTo(value.strip());
	}

	@Test
	void guardsCellsThatSpreadsheetsWouldRunAsFormulas() {
		assertThat(VocabularyCsv.escape("=1+1")).isEqualTo("'=1+1");
		assertThat(VocabularyCsv.escape("-ung")).isEqualTo("'-ung");
		assertThat(VocabularyCsv.escape("Hund")).isEqualTo("Hund");
	}

	@Test
	void readsColumnsByHeaderIncludingAliases() {
		List<ImportRow> rows = VocabularyCsv.read("""
				Word;Level;POS;Gender;Plural;Status;Tags;Ignored
				der Hund;a1;noun;masculine;die Hunde;mastered;Tiere | tiere|Alltag ;x
				""", 10);
		ImportRow dog = rows.getFirst();
		assertThat(dog.error()).isNull();
		assertThat(dog.line()).isEqualTo(2);
		assertThat(dog.lemma()).isEqualTo("der Hund");
		assertThat(dog.cefrLevel()).isEqualTo(CefrLevel.A1);
		assertThat(dog.partOfSpeech()).isEqualTo(PartOfSpeech.NOUN);
		assertThat(dog.gender()).isEqualTo(Gender.MASCULINE);
		assertThat(dog.plural()).isEqualTo("die Hunde");
		assertThat(dog.status()).isEqualTo(KnowledgeStatus.MASTERED);
		assertThat(dog.tags()).containsExactly("Alltag", "Tiere");
	}

	@Test
	void flagsBadRows() {
		List<ImportRow> rows = VocabularyCsv.read("lemma,gender,tags\n,,\n  ,x,\nHund,thing,\nKatze,,"
				+ "x".repeat(51) + "\n", 10);
		assertThat(rows).extracting(ImportRow::error)
			.containsExactly("has no word", "unknown gender 'thing'", "tag must be at most 50 characters");
	}

	@Test
	void needsAHeaderWithALemmaColumnAndCapsTheRows() {
		assertThatThrownBy(() -> VocabularyCsv.read("der Hund,dog\n", 10)).isInstanceOf(InvalidFieldException.class)
			.hasMessageContaining("header row with a lemma column");
		assertThatThrownBy(() -> VocabularyCsv.read("", 10)).isInstanceOf(InvalidFieldException.class);
		assertThatThrownBy(() -> VocabularyCsv.read("lemma\na\nb\nc\n", 2)).isInstanceOf(InvalidFieldException.class)
			.hasMessageContaining("more than 2 rows");
	}

}
