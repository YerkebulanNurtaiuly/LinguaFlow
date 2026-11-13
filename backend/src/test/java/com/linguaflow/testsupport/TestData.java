package com.linguaflow.testsupport;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.linguaflow.grammar.TitleNormalizer;
import com.linguaflow.vocabulary.LemmaNormalizer;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builders for learning material, written straight to the database (QA-01), so a test
 * states only what matters to it. Everything belongs to one learner in German, which
 * {@link TestUsers#learner} sets up; normalized lemmas and titles come from the real
 * normalizers.
 * <pre>{@code
 * TestData data = new TestData(jdbc);
 * UUID hund = data.word(anna.id(), "der Hund").translation("dog").noun("MASCULINE").insert();
 * UUID dativ = data.topic(anna.id(), "Dativ").status("LEARNING").insert();
 * ExerciseSetIds set = data.exerciseSet(anna.id())
 *     .with(TestData.fillBlank("Ich helfe ___ Mann.", "dem").topics(dativ).words(hund))
 *     .insert();
 * }</pre>
 * Words are READY (enriched) unless told otherwise. Nothing here publishes events, so
 * no flashcards, session links or progress rows appear on their own.
 */
public class TestData {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String LANGUAGE = "de";

	private final JdbcTemplate jdbc;

	public TestData(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public WordBuilder word(UUID userId, String lemma) {
		return new WordBuilder(userId, lemma);
	}

	public TopicBuilder topic(UUID userId, String title) {
		return new TopicBuilder(userId, title);
	}

	public LessonBuilder lesson(UUID userId, String title) {
		return new LessonBuilder(userId, title);
	}

	public ExerciseSetBuilder exerciseSet(UUID userId) {
		return new ExerciseSetBuilder(userId);
	}

	/** A FILL_BLANK exercise with one gap per answer. */
	public static ExerciseBuilder fillBlank(String text, String... answers) {
		List<Map<String, Object>> blanks = new ArrayList<>();
		for (String answer : answers) {
			blanks.add(Map.of("answer", answer, "alternatives", List.of()));
		}
		return new ExerciseBuilder("FILL_BLANK", Map.of("text", text), Map.of("blanks", blanks));
	}

	public static ExerciseBuilder multipleChoice(String question, List<String> options, int correctIndex) {
		return new ExerciseBuilder("MULTIPLE_CHOICE", Map.of("question", question, "options", options),
				Map.of("correctIndex", correctIndex));
	}

	/** A TRANSLATION into German; answers other than {@code answer} go to the AI judge. */
	public static ExerciseBuilder translation(String text, String answer) {
		return new ExerciseBuilder("TRANSLATION", Map.of("direction", "NATIVE_TO_TARGET", "text", text),
				Map.of("answer", answer, "alternatives", List.of()));
	}

	public final class WordBuilder {

		private final UUID userId;

		private final String lemma;

		private String translation;

		private String partOfSpeech;

		private String gender;

		private String plural;

		private String cefrLevel;

		private String status = "LEARNING";

		private String aiStatus = "READY";

		private final List<UUID> lessons = new ArrayList<>();

		private WordBuilder(UUID userId, String lemma) {
			this.userId = userId;
			this.lemma = lemma;
		}

		public WordBuilder translation(String translation) {
			this.translation = translation;
			return this;
		}

		public WordBuilder partOfSpeech(String partOfSpeech) {
			this.partOfSpeech = partOfSpeech;
			return this;
		}

		/** A noun with its gender (MASCULINE, FEMININE or NEUTER). */
		public WordBuilder noun(String gender) {
			this.partOfSpeech = "NOUN";
			this.gender = gender;
			return this;
		}

		public WordBuilder plural(String plural) {
			this.plural = plural;
			return this;
		}

		public WordBuilder cefrLevel(String cefrLevel) {
			this.cefrLevel = cefrLevel;
			return this;
		}

		public WordBuilder status(String status) {
			this.status = status;
			return this;
		}

		public WordBuilder aiStatus(String aiStatus) {
			this.aiStatus = aiStatus;
			return this;
		}

		/** Attaches the word to a lesson (study session). */
		public WordBuilder inLesson(UUID lessonId) {
			lessons.add(lessonId);
			return this;
		}

		public UUID insert() {
			UUID id = UUID.randomUUID();
			jdbc.update("insert into vocabulary_items (id, user_id, language_code, lemma, normalized_lemma, "
					+ "part_of_speech, translation, gender, plural, cefr_level, status, ai_status) "
					+ "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, userId, LANGUAGE, lemma,
					LemmaNormalizer.normalize(LANGUAGE, lemma), partOfSpeech, translation, gender, plural, cefrLevel,
					status, aiStatus);
			for (UUID lesson : lessons) {
				jdbc.update("insert into session_vocabulary (study_session_id, vocabulary_item_id) values (?, ?)",
						lesson, id);
			}
			return id;
		}

	}

	public final class TopicBuilder {

		private final UUID userId;

		private final String title;

		private String description;

		private String cefrLevel;

		private String status = "NEW";

		private final List<UUID> lessons = new ArrayList<>();

		private TopicBuilder(UUID userId, String title) {
			this.userId = userId;
			this.title = title;
		}

		public TopicBuilder description(String description) {
			this.description = description;
			return this;
		}

		public TopicBuilder cefrLevel(String cefrLevel) {
			this.cefrLevel = cefrLevel;
			return this;
		}

		public TopicBuilder status(String status) {
			this.status = status;
			return this;
		}

		public TopicBuilder inLesson(UUID lessonId) {
			lessons.add(lessonId);
			return this;
		}

		public UUID insert() {
			UUID id = UUID.randomUUID();
			jdbc.update("insert into grammar_topics (id, user_id, language_code, title, normalized_title, "
					+ "description, cefr_level, status) values (?, ?, ?, ?, ?, ?, ?, ?)", id, userId, LANGUAGE, title,
					TitleNormalizer.normalize(LANGUAGE, title), description, cefrLevel, status);
			for (UUID lesson : lessons) {
				jdbc.update("insert into session_grammar (study_session_id, grammar_topic_id) values (?, ?)",
						lesson, id);
			}
			return id;
		}

	}

	public final class LessonBuilder {

		private final UUID userId;

		private final String title;

		private LocalDate studiedOn = LocalDate.of(2026, 11, 2);

		private boolean active;

		private LessonBuilder(UUID userId, String title) {
			this.userId = userId;
			this.title = title;
		}

		public LessonBuilder studiedOn(LocalDate studiedOn) {
			this.studiedOn = studiedOn;
			return this;
		}

		/** Makes it the active lesson; only one per language may be. */
		public LessonBuilder active() {
			this.active = true;
			return this;
		}

		public UUID insert() {
			UUID id = UUID.randomUUID();
			jdbc.update("insert into study_sessions (id, user_id, language_code, title, studied_on, active) "
					+ "values (?, ?, ?, ?, ?, ?)", id, userId, LANGUAGE, title, studiedOn, active);
			return id;
		}

	}

	public final class ExerciseSetBuilder {

		private final UUID userId;

		private String source = "MANUAL";

		private String status = "READY";

		private final List<ExerciseBuilder> exercises = new ArrayList<>();

		private ExerciseSetBuilder(UUID userId) {
			this.userId = userId;
		}

		public ExerciseSetBuilder source(String source) {
			this.source = source;
			return this;
		}

		public ExerciseSetBuilder status(String status) {
			this.status = status;
			return this;
		}

		public ExerciseSetBuilder with(ExerciseBuilder... exercises) {
			this.exercises.addAll(List.of(exercises));
			return this;
		}

		/** Inserts the set and its exercises, in the order given. */
		public ExerciseSetIds insert() {
			UUID setId = UUID.randomUUID();
			// The request is read back into ExerciseSetRequest, which needs a positive count
			Map<String, Object> request = Map.of("count", Math.max(1, exercises.size()));
			jdbc.update("insert into exercise_sets (id, user_id, language_code, source, request, status) "
					+ "values (?, ?, ?, ?, ?::jsonb, ?)", setId, userId, LANGUAGE, source, json(request), status);
			List<UUID> ids = new ArrayList<>();
			for (int position = 0; position < exercises.size(); position++) {
				ids.add(exercises.get(position).insert(jdbc, setId, userId, position));
			}
			return new ExerciseSetIds(setId, List.copyOf(ids));
		}

	}

	/** One exercise; added to a set with {@link ExerciseSetBuilder#with}. */
	public static final class ExerciseBuilder {

		private final String type;

		private final Map<String, Object> prompt;

		private final Map<String, Object> solution;

		private final List<UUID> topics = new ArrayList<>();

		private final List<UUID> words = new ArrayList<>();

		private ExerciseBuilder(String type, Map<String, Object> prompt, Map<String, Object> solution) {
			this.type = type;
			this.prompt = new LinkedHashMap<>(prompt);
			this.solution = new LinkedHashMap<>(solution);
		}

		public ExerciseBuilder topics(UUID... topicIds) {
			topics.addAll(List.of(topicIds));
			return this;
		}

		public ExerciseBuilder words(UUID... wordIds) {
			words.addAll(List.of(wordIds));
			return this;
		}

		public ExerciseBuilder explanation(String explanation) {
			solution.put("explanation", explanation);
			return this;
		}

		private UUID insert(JdbcTemplate jdbc, UUID setId, UUID userId, int position) {
			UUID id = UUID.randomUUID();
			jdbc.update("insert into exercises (id, exercise_set_id, user_id, position, type, prompt, solution) "
					+ "values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)", id, setId, userId, position, type, json(prompt),
					json(solution));
			for (UUID topic : topics) {
				jdbc.update("insert into exercise_grammar_topics (exercise_id, grammar_topic_id) values (?, ?)", id,
						topic);
			}
			for (UUID word : words) {
				jdbc.update("insert into exercise_vocabulary (exercise_id, vocabulary_item_id) values (?, ?)", id,
						word);
			}
			return id;
		}

	}

	/** The IDs of an inserted set and its exercises, in position order. */
	public record ExerciseSetIds(UUID id, List<UUID> exercises) {

		public UUID exercise(int position) {
			return exercises.get(position);
		}

	}

	private static String json(Object value) {
		return JSON.writeValueAsString(value);
	}

}
