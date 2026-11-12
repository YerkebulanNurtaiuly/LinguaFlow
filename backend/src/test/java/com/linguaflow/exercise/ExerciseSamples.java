package com.linguaflow.exercise;

import java.util.List;

import com.linguaflow.common.domain.CardDirection;
import com.linguaflow.common.domain.ExerciseType;
import com.linguaflow.exercise.ExercisePrompt.ErrorCorrection;
import com.linguaflow.exercise.ExercisePrompt.FillBlank;
import com.linguaflow.exercise.ExercisePrompt.MultipleChoice;
import com.linguaflow.exercise.ExercisePrompt.SentenceConstruction;
import com.linguaflow.exercise.ExercisePrompt.Transformation;
import com.linguaflow.exercise.ExercisePrompt.Translation;
import com.linguaflow.exercise.ExercisePrompt.WordOrder;
import com.linguaflow.exercise.ExerciseSolution.Blank;
import com.linguaflow.exercise.ExerciseSolution.Blanks;
import com.linguaflow.exercise.ExerciseSolution.Choice;
import com.linguaflow.exercise.ExerciseSolution.TextAnswer;
import com.linguaflow.exercise.ExerciseSolution.TokenOrder;

/** One valid Dativ exercise of each type. */
final class ExerciseSamples {

	private ExerciseSamples() {
	}

	static ExerciseContent of(ExerciseType type) {
		return switch (type) {
			case FILL_BLANK -> new ExerciseContent(type,
					new FillBlank(null, "Ich gebe ___ Mann ___ Buch.", "(der Mann, das Buch)"),
					new Blanks(List.of(new Blank("dem", List.of()), new Blank("das", List.of())),
							"Geben takes the person in the Dativ."));
			case MULTIPLE_CHOICE -> new ExerciseContent(type,
					new MultipleChoice("Choose the right article.", "Ich helfe ___ Frau.",
							List.of("die", "der", "den")),
					new Choice(1, "Helfen takes the Dativ: der Frau."));
			case TRANSLATION -> new ExerciseContent(type,
					new Translation(null, CardDirection.NATIVE_TO_TARGET, "I give the child an apple."),
					new TextAnswer("Ich gebe dem Kind einen Apfel.", List.of("Ich gebe einem Kind einen Apfel."),
							null));
			case SENTENCE_CONSTRUCTION -> new ExerciseContent(type,
					new SentenceConstruction(null, "Say that the book belongs to your brother.",
							List.of("gehören", "Bruder", "Buch")),
					new TextAnswer("Das Buch gehört meinem Bruder.", List.of(), null));
			case ERROR_CORRECTION -> new ExerciseContent(type,
					new ErrorCorrection(null, "Ich danke dich für die Hilfe."),
					new TextAnswer("Ich danke dir für die Hilfe.", List.of(), "Danken takes the Dativ."));
			case WORD_ORDER -> new ExerciseContent(type,
					new WordOrder(null, List.of("dem", "Ich", "Hund", "gebe", "Wasser", ".")),
					new TokenOrder(List.of("Ich", "gebe", "dem", "Hund", "Wasser", "."),
							List.of(List.of("Wasser", "gebe", "ich", "dem", "Hund", ".")), null));
			case TRANSFORMATION -> new ExerciseContent(type,
					new Transformation(null, "Ich schenke den Kindern Bücher.", "Replace the people with a pronoun."),
					new TextAnswer("Ich schenke ihnen Bücher.", List.of(), null));
		};
	}

}
