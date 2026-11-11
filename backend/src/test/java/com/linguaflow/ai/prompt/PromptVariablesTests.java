package com.linguaflow.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.linguaflow.common.domain.CefrLevel;
import org.junit.jupiter.api.Test;

class PromptVariablesTests {

	@Test
	void namesLanguagesInEnglish() {
		assertThat(PromptVariables.forLanguages("de", "en").values()).containsEntry(PromptVar.TARGET_LANGUAGE, "German")
			.containsEntry(PromptVar.EXPLANATION_LANGUAGE, "English");
		assertThat(PromptVariables.languageName("ru")).isEqualTo("Russian");
		assertThatThrownBy(() -> PromptVariables.languageName("xx")).hasMessageContaining("Unknown language code");
	}

	@Test
	void writesValuesTheWayTemplatesExpectThem() {
		Map<PromptVar, String> values = PromptVariables.forLanguages("de", "en")
			.set(PromptVar.WORD, "  der Hund\n")
			.set(PromptVar.COUNT, 5)
			.set(PromptVar.CEFR_LEVEL, CefrLevel.B1)
			.set(PromptVar.KNOWN_VOCABULARY, List.of("Hund", "", "eine\nKatze"))
			.set(PromptVar.FOCUS_TOPICS, Set.of())
			.set(PromptVar.TRANSLATION_HINT, "   ")
			.values();

		assertThat(values).containsEntry(PromptVar.WORD, "der Hund")
			.containsEntry(PromptVar.COUNT, "5")
			.containsEntry(PromptVar.CEFR_LEVEL, "B1")
			// One line per item, so an item can't add lines of its own
			.containsEntry(PromptVar.KNOWN_VOCABULARY, "- Hund\n- eine Katze")
			// Empty values count as not set
			.doesNotContainKeys(PromptVar.FOCUS_TOPICS, PromptVar.TRANSLATION_HINT);
	}

	@Test
	void settingNullRemovesAValue() {
		PromptVariables variables = PromptVariables.forLanguages("de", "en").set(PromptVar.WORD, "Hund");
		variables.set(PromptVar.WORD, null);
		assertThat(variables.values()).doesNotContainKey(PromptVar.WORD);
		assertThatThrownBy(() -> variables.set(PromptVar.WORD, new Object()))
			.hasMessageContaining("Unsupported prompt value type");
	}

}
