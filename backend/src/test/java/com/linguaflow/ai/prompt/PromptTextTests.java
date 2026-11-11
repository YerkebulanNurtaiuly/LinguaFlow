package com.linguaflow.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class PromptTextTests {

	private static final Function<String, String> NO_PARTIALS = name -> {
		throw new IllegalStateException("missing partial " + name);
	};

	private static String render(String template, Map<String, String> values) {
		return PromptText.render(PromptText.prepare(template, NO_PARTIALS), values);
	}

	@Test
	void insertsVariables() {
		assertThat(render("Enrich <word>{{word}}</word> in {{lang}}.", Map.of("word", "Hund", "lang", "German")))
			.isEqualTo("Enrich <word>Hund</word> in German.");
	}

	@Test
	void keepsSectionsOnlyWhenTheirVariableIsSet() {
		String template = "Word{{#level}} at {{level}}{{/level}}{{^level}} at any level{{/level}}.";
		assertThat(render(template, Map.of("level", "A2"))).isEqualTo("Word at A2.");
		assertThat(render(template, Map.of())).isEqualTo("Word at any level.");
	}

	@Test
	void standaloneTagsLeaveNoBlankLines() {
		String template = """
				Line one
				{{#known}}

				Known words:
				{{known}}
				{{/known}}
				{{#level}}
				Level: {{level}}
				{{/level}}
				Last line
				""";
		assertThat(render(template, Map.of())).isEqualTo("Line one\nLast line");
		assertThat(render(template, Map.of("known", "- Hund\n- Katze", "level", "B1")))
			.isEqualTo("Line one\n\nKnown words:\n- Hund\n- Katze\nLevel: B1\nLast line");
	}

	@Test
	void sectionsWithDifferentNamesNest() {
		String template = "{{#a}}A{{#b}} and B{{/b}}{{/a}}";
		assertThat(render(template, Map.of("a", "x", "b", "y"))).isEqualTo("A and B");
		assertThat(render(template, Map.of("a", "x"))).isEqualTo("A");
		assertThat(render(template, Map.of("b", "y"))).isEmpty();
	}

	@Test
	void insertedValuesAreNeverParsed() {
		assertThat(render("<word>{{word}}</word>", Map.of("word", "{{#x}}{{secret}}{{/x}} $1 \\")))
			.isEqualTo("<word>{{#x}}{{secret}}{{/x}} $1 \\</word>");
	}

	@Test
	void resolvesPartialsAndNormalizesLineEndings() {
		Map<String, String> partials = Map.of("rules", "Rule one.\r\nRule two: {{lang}}.\r\n", "outer",
				"Outer.\n{{> rules}}");
		String prepared = PromptText.prepare("Start\r\n{{> outer}}\r\nEnd\r\n", partials::get);

		assertThat(prepared).doesNotContain("\r");
		assertThat(PromptText.render(prepared, Map.of("lang", "German")))
			.isEqualTo("Start\nOuter.\nRule one.\nRule two: German.\nEnd");
		assertThat(PromptText.variableNames(prepared)).containsExactly("lang");
	}

	@Test
	void refusesMalformedTemplates() {
		assertThatThrownBy(() -> PromptText.prepare("{{#a}}never closed", NO_PARTIALS))
			.hasMessageContaining("unclosed");
		assertThatThrownBy(() -> PromptText.prepare("{{ spaced }}", NO_PARTIALS)).hasMessageContaining("Malformed");
		assertThatThrownBy(() -> PromptText.prepare("{{#a}}x{{/b}}", NO_PARTIALS)).hasMessageContaining("Malformed");
		assertThatThrownBy(() -> PromptText.prepare("{{> self}}", name -> "{{> self}}"))
			.hasMessageContaining("nest deeper");
	}

	@Test
	void aVariableOutsideASectionMustHaveAValue() {
		assertThatThrownBy(() -> render("Hello {{name}}", Map.of())).hasMessageContaining("{{name}}");
	}

}
