package com.linguaflow.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * An offline {@link LlmClient} with canned answers that pass every output check (QA-05),
 * chosen with {@code linguaflow.ai.provider=scripted} (env {@code AI_PROVIDER}). The
 * end-to-end tests run the whole stack on it, so words get enriched, flashcards appear
 * and exercise sets fill up without a key or any cost. It also suits demos.
 * <p>
 * The answers are scripted, not intelligent:
 * <ul>
 * <li><b>Words</b> come from a small dictionary; any other word gets a placeholder
 * translation in brackets and a note that the answer is scripted.</li>
 * <li><b>Exercises</b> come from fixed fill-in-the-blank, multiple-choice and word-order
 * banks, skipping ones the prompt lists as already made. Other formats get none, so a set
 * asking for them ends up smaller.</li>
 * <li><b>Explanations and grading</b> are generic texts.</li>
 * </ul>
 * Unlike {@link FakeLlmClient} it records nothing, so it can run for a long time.
 */
public class ScriptedLlmClient implements LlmClient {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String NOTE = "Scripted answer: no AI model is configured.";

	private static final Pattern WORDS = Pattern.compile("<words>\\n(.*?)\\n</words>", Pattern.DOTALL);

	private static final Pattern WORD = Pattern.compile("<word>(.*?)</word>");

	private static final Pattern MARKED = Pattern.compile("<part_of_speech>(.*?)</part_of_speech>");

	private static final Pattern COUNT = Pattern.compile("Write (\\d+) exercises");

	private static final Pattern FOCUS_TOPICS = Pattern.compile("<focus_topics>\\n(.*?)\\n</focus_topics>",
			Pattern.DOTALL);

	private static final Pattern TOPIC = Pattern.compile("<topic>(.*?)</topic>");

	/** What the dictionary knows about a word. */
	private record Entry(String partOfSpeech, @Nullable String gender, @Nullable String plural, String translation,
			String cefrLevel, @Nullable Map<String, String> verbForms, List<List<String>> examples) {
	}

	private static Entry noun(String gender, String plural, String translation, String sentence1,
			String translation1, String sentence2, String translation2) {
		return new Entry("NOUN", gender, plural, translation, "A1", null,
				List.of(List.of(sentence1, translation1), List.of(sentence2, translation2)));
	}

	private static Entry verb(String translation, String praeteritum, String partizip2, String auxiliary,
			String sentence1, String translation1, String sentence2, String translation2) {
		return new Entry("VERB", null, null, translation, "A1",
				Map.of("praeteritum", praeteritum, "partizip2", partizip2, "auxiliary", auxiliary),
				List.of(List.of(sentence1, translation1), List.of(sentence2, translation2)));
	}

	private static Entry other(String partOfSpeech, String translation, String sentence1, String translation1,
			String sentence2, String translation2) {
		return new Entry(partOfSpeech, null, null, translation, "A1", null,
				List.of(List.of(sentence1, translation1), List.of(sentence2, translation2)));
	}

	private static final Map<String, Entry> DICTIONARY = Map.ofEntries(
			Map.entry("hund", noun("MASCULINE", "Hunde", "dog", "Der Hund schläft.", "The dog is sleeping.",
					"Wir haben zwei Hunde.", "We have two dogs.")),
			Map.entry("katze", noun("FEMININE", "Katzen", "cat", "Die Katze trinkt Milch.", "The cat drinks milk.",
					"Die Katzen spielen.", "The cats are playing.")),
			Map.entry("haus", noun("NEUTER", "Häuser", "house", "Das Haus ist alt.", "The house is old.",
					"Ich bin zu Hause.", "I am at home.")),
			Map.entry("buch", noun("NEUTER", "Bücher", "book", "Das Buch ist spannend.", "The book is exciting.",
					"Ich lese ein Buch.", "I am reading a book.")),
			Map.entry("tisch", noun("MASCULINE", "Tische", "table", "Der Tisch ist rund.", "The table is round.",
					"Das Essen steht auf dem Tisch.", "The food is on the table.")),
			Map.entry("frau", noun("FEMININE", "Frauen", "woman", "Die Frau liest.", "The woman is reading.",
					"Ich helfe der Frau.", "I am helping the woman.")),
			Map.entry("mann", noun("MASCULINE", "Männer", "man", "Der Mann kocht.", "The man is cooking.",
					"Ich gebe dem Mann das Buch.", "I give the man the book.")),
			Map.entry("kind", noun("NEUTER", "Kinder", "child", "Das Kind spielt.", "The child is playing.",
					"Die Kinder lachen.", "The children are laughing.")),
			Map.entry("laufen", verb("to run; to walk", "lief", "gelaufen", "SEIN", "Ich laufe jeden Morgen.",
					"I run every morning.", "Er ist nach Hause gelaufen.", "He walked home.")),
			Map.entry("gehen", verb("to go", "ging", "gegangen", "SEIN", "Wir gehen ins Kino.",
					"We are going to the cinema.", "Sie ist früh gegangen.", "She left early.")),
			Map.entry("essen", verb("to eat", "aß", "gegessen", "HABEN", "Wir essen um sieben.", "We eat at seven.",
					"Hast du schon gegessen?", "Have you eaten yet?")),
			Map.entry("helfen", verb("to help", "half", "geholfen", "HABEN", "Ich helfe dir.", "I am helping you.",
					"Sie hat mir geholfen.", "She helped me.")),
			Map.entry("geben", verb("to give", "gab", "gegeben", "HABEN", "Ich gebe dir das Buch.",
					"I give you the book.", "Er hat mir Geld gegeben.", "He gave me money.")),
			Map.entry("schön", other("ADJECTIVE", "beautiful; nice", "Das Wetter ist schön.", "The weather is nice.",
					"Sie hat eine schöne Stimme.", "She has a beautiful voice.")),
			Map.entry("groß", other("ADJECTIVE", "big; tall", "Das Haus ist groß.", "The house is big.",
					"Mein Bruder ist sehr groß.", "My brother is very tall.")),
			Map.entry("schnell", other("ADJECTIVE", "fast; quick", "Das Auto ist schnell.", "The car is fast.",
					"Er läuft schnell.", "He runs fast.")));

	/** One canned exercise: prompt, solution and a sentence to recognise it in "already made" lists. */
	private record Canned(String key, Map<String, Object> prompt, Map<String, Object> solution) {
	}

	private static Canned gap(String text, String answer, String explanation) {
		return new Canned(text, map("instruction", null, "text", text, "hint", null),
				map("blanks", List.of(map("answer", answer, "alternatives", List.of())), "explanation", explanation));
	}

	private static Canned choice(String question, List<String> options, int correct, String explanation) {
		return new Canned(question, map("instruction", null, "question", question, "options", options),
				map("correctIndex", correct, "explanation", explanation));
	}

	private static Canned order(List<String> shuffled, List<String> solution, String explanation) {
		return new Canned(String.join(" ", solution), map("instruction", null, "tokens", shuffled),
				map("tokens", solution, "alternatives", List.of(), "explanation", explanation));
	}

	private static final List<Canned> FILL_BLANK = List.of(
			gap("Ich gebe ___ Hund Wasser.", "dem", "geben takes the receiver in the dative: dem Hund."),
			gap("Wir helfen ___ Frau.", "der", "helfen takes the dative: der Frau."),
			gap("Das Buch gehört ___ Kind.", "dem", "gehören takes the dative: dem Kind."),
			gap("Er dankt ___ Lehrer.", "dem", "danken takes the dative: dem Lehrer."),
			gap("Ich sehe ___ Mann.", "den", "sehen takes the accusative: den Mann."),
			gap("Sie kauft ___ Tisch.", "einen", "kaufen takes the accusative: einen Tisch."),
			gap("Gestern ___ ich ins Kino gegangen.", "bin", "gehen forms the Perfekt with sein."),
			gap("Wir ___ Pizza gegessen.", "haben", "essen forms the Perfekt with haben."),
			gap("Er ist nach Hause ___.", "gelaufen", "The Partizip II of laufen is gelaufen."),
			gap("Ich fahre mit ___ Bahn.", "der", "mit always takes the dative: der Bahn."),
			gap("Das Geschenk ist für ___ Mutter.", "meine", "für always takes the accusative: meine Mutter."),
			gap("Die Katze schläft unter ___ Tisch.", "dem", "unter with a location takes the dative: dem Tisch."));

	private static final List<Canned> MULTIPLE_CHOICE = List.of(
			choice("Ich helfe ___ Mann.", List.of("der", "dem", "den"), 1, "helfen takes the dative: dem Mann."),
			choice("Er gibt ___ Kind einen Apfel.", List.of("dem", "das", "den"), 0,
					"The receiver of geben is dative: dem Kind."),
			choice("Wir sehen ___ Hund.", List.of("der", "dem", "den"), 2, "sehen takes the accusative: den Hund."),
			choice("Sie ___ nach Berlin gefahren.", List.of("hat", "ist", "wird"), 1,
					"fahren forms the Perfekt with sein."),
			choice("Ich ___ ein Buch gelesen.", List.of("habe", "bin", "werde"), 0,
					"lesen forms the Perfekt with haben."),
			choice("Das Fahrrad gehört ___ Schwester.", List.of("meine", "meiner", "meinen"), 1,
					"gehören takes the dative: meiner Schwester."),
			choice("Wir warten auf ___ Bus.", List.of("dem", "den", "der"), 1,
					"warten auf takes the accusative: den Bus."),
			choice("Er kommt aus ___ Schweiz.", List.of("die", "der", "den"), 1, "aus takes the dative: der Schweiz."),
			choice("Ich bin gestern ___.", List.of("geschwimmt", "geschwommen", "schwamm"), 1,
					"The Partizip II of schwimmen is geschwommen."),
			choice("Die Kinder spielen mit ___ Ball.", List.of("dem", "den", "der"), 0,
					"mit takes the dative: dem Ball."));

	private static final List<Canned> WORD_ORDER = List.of(
			order(List.of("Wasser.", "gebe", "dem", "Ich", "Hund"), List.of("Ich", "gebe", "dem", "Hund", "Wasser."),
					"The verb comes second in a main clause."),
			order(List.of("gegangen.", "bin", "Kino", "ins", "Ich"), List.of("Ich", "bin", "ins", "Kino", "gegangen."),
					"In the Perfekt the participle goes to the end."),
			order(List.of("Frau.", "helfen", "der", "Wir"), List.of("Wir", "helfen", "der", "Frau."),
					"The verb comes second; helfen takes the dative."),
			order(List.of("gegessen.", "Pizza", "haben", "Wir"), List.of("Wir", "haben", "Pizza", "gegessen."),
					"The auxiliary is second, the participle last."),
			order(List.of("Hause.", "nach", "Er", "geht"), List.of("Er", "geht", "nach", "Hause."),
					"The verb comes second in a main clause."),
			order(List.of("Bahn.", "fahre", "der", "Ich", "mit"), List.of("Ich", "fahre", "mit", "der", "Bahn."),
					"The verb comes second; mit takes the dative."));

	@Override
	public LlmResponse complete(LlmPrompt prompt, @Nullable Map<String, Object> jsonSchema, LlmOptions options) {
		Object answer = switch (options.useCase()) {
			case VOCABULARY_ENRICHMENT -> enrichment(prompt.user());
			case GRAMMAR_EXPLANATION -> explanation(find(TOPIC, prompt.user(), "this topic"));
			case EXERCISE_GENERATION -> exercises(prompt.system(), prompt.user());
			case ANSWER_EVALUATION -> map("correct", false, "score", 0.0, "errors", List.of(), "correctedAnswer",
					null, "feedback", "This answer can't be graded without an AI model. " + NOTE);
			case MISTAKE_EXPLANATION -> map("answerIsCorrect", false, "mistake",
					"Compare your answer with the correct answer shown above.", "rule", NOTE, "topic", null,
					"example", null, "otherErrors", List.of());
		};
		String text = JSON.writeValueAsString(answer);
		return new LlmResponse(text, "scripted", 0, 0, 0, 0, Duration.ZERO);
	}

	@Override
	public String provider() {
		return "scripted";
	}

	private static Object enrichment(String user) {
		Matcher batch = WORDS.matcher(user);
		if (batch.find()) {
			List<Map<String, Object>> words = new ArrayList<>();
			for (String line : batch.group(1).split("\n")) {
				String item = line.startsWith("- ") ? line.substring(2).strip() : line.strip();
				Matcher marked = Pattern.compile("^(.*) \\(([a-z]+)\\)$").matcher(item);
				words.add(marked.matches()
						? word(item, marked.group(1), marked.group(2).toUpperCase(Locale.ROOT))
						: word(item, item, null));
			}
			return Map.of("words", words);
		}
		String word = find(WORD, user, "");
		String marked = find(MARKED, user, null);
		return word(word, word, marked == null ? null : marked.toUpperCase(Locale.ROOT));
	}

	/** One enrichment entry; {@code echo} is the word as the prompt listed it. */
	private static Map<String, Object> word(String echo, String lemma, @Nullable String marked) {
		String key = lemma.toLowerCase(Locale.ROOT).replaceFirst("^(der|die|das) ", "");
		Entry entry = DICTIONARY.get(key);
		if (entry != null && (marked == null || marked.equals(entry.partOfSpeech()))) {
			List<Map<String, Object>> examples = entry.examples()
				.stream()
				.map(pair -> map("sentence", pair.get(0), "translation", pair.get(1)))
				.toList();
			return map("word", echo, "partOfSpeech", entry.partOfSpeech(), "gender", entry.gender(), "plural",
					entry.plural(), "translation", entry.translation(), "cefrLevel", entry.cefrLevel(), "verbForms",
					entry.verbForms(), "examples", examples, "notes", null);
		}
		// Unknown: a placeholder that passes the rules and says what it is
		String partOfSpeech = marked != null ? marked
				: !lemma.isEmpty() && Character.isUpperCase(lemma.codePointAt(0)) ? "NOUN" : "OTHER";
		Map<String, String> verbForms = partOfSpeech.equals("VERB") ? Map.of("praeteritum",
				key.replaceFirst("e?n$", "") + "te", "partizip2", "ge" + key.replaceFirst("e?n$", "") + "t",
				"auxiliary", "HABEN") : null;
		return map("word", echo, "partOfSpeech", partOfSpeech, "gender",
				partOfSpeech.equals("NOUN") ? "NEUTER" : null, "plural", null, "translation", "[" + lemma + "]",
				"cefrLevel", null, "verbForms", verbForms, "examples",
				List.of(map("sentence", "Das Wort „" + lemma + "“ steht in diesem Satz.", "translation",
						"The word \"" + lemma + "\" is in this sentence."),
						map("sentence", "Ich lerne heute „" + lemma + "“.", "translation",
								"Today I am learning \"" + lemma + "\".")),
				"notes", NOTE);
	}

	private static Object explanation(String title) {
		return map("summary", title + " explained by a scripted answer. " + NOTE, "rules",
				List.of("Look at how " + title + " changes the words around it.",
						"Practise it in short sentences first."),
				"examples",
				List.of(map("sentence", "Ich gebe dem Hund Wasser.", "translation", "I give the dog water."),
						map("sentence", "Wir sind nach Hause gegangen.", "translation", "We went home.")),
				"commonMistakes", List.of("Mixing up the dative and the accusative."));
	}

	private static Object exercises(String system, String user) {
		List<Canned> bank = system.contains("Format: fill in the blank.") ? FILL_BLANK
				: system.contains("Format: multiple choice.") ? MULTIPLE_CHOICE
						: system.contains("Format: word order.") ? WORD_ORDER : List.of();
		int count = Integer.parseInt(find(COUNT, user, "0"));
		List<String> topics = new ArrayList<>();
		Matcher focus = FOCUS_TOPICS.matcher(user);
		if (focus.find()) {
			for (String line : focus.group(1).split("\n")) {
				topics.add(line.startsWith("- ") ? line.substring(2).strip() : line.strip());
			}
		}
		List<Map<String, Object>> items = new ArrayList<>();
		for (Canned canned : bank) {
			if (items.size() == count) {
				break;
			}
			// Recent and already-made exercises are listed in the prompt: skip them
			if (user.contains(canned.key())) {
				continue;
			}
			items.add(map("prompt", canned.prompt(), "solution", canned.solution(), "words", List.of(), "topics",
					topics));
		}
		return Map.of("exercises", items);
	}

	private static @Nullable String find(Pattern pattern, String text, @Nullable String fallback) {
		Matcher matcher = pattern.matcher(text);
		return matcher.find() ? matcher.group(1).strip() : fallback;
	}

	/** A map that keeps nulls and order, as the answer records expect every field. */
	private static Map<String, Object> map(Object... keysAndValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			map.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return map;
	}

}
