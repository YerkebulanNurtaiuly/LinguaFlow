import type { ExerciseType } from '@/exercises/exercisesApi'

export const TYPE_LABELS: Record<ExerciseType, string> = {
  FILL_BLANK: 'Fill in the blank',
  MULTIPLE_CHOICE: 'Multiple choice',
  TRANSLATION: 'Translation',
  SENTENCE_CONSTRUCTION: 'Build a sentence',
  ERROR_CORRECTION: 'Fix the mistake',
  WORD_ORDER: 'Word order',
  TRANSFORMATION: 'Rewrite the sentence',
}

/** What each type asks, when the exercise brings no instruction of its own. */
export const DEFAULT_INSTRUCTIONS: Record<ExerciseType, string> = {
  FILL_BLANK: 'Fill in the gaps.',
  MULTIPLE_CHOICE: 'Choose the right answer.',
  TRANSLATION: 'Translate the sentence.',
  SENTENCE_CONSTRUCTION: 'Write one sentence that uses all the words.',
  ERROR_CORRECTION: 'Correct the mistake and write the whole sentence.',
  WORD_ORDER: 'Put the words in the right order.',
  TRANSFORMATION: 'Rewrite the sentence as the task says.',
}

/** A gap in a fill-in-the-blank text: three or more underscores, as the backend has it. */
const GAP = /_{3,}/

/** The text around the gaps: n gaps give n + 1 pieces. */
export function splitGaps(text: string): string[] {
  return text.split(GAP)
}

export const NOTE_LABELS = {
  CAPITALIZATION: 'Watch the capital letters.',
  UMLAUT_SPELLING:
    'Accepted with ae, oe, ue or ss; the usual spelling uses ä, ö, ü or ß.',
  ALTERNATIVE: 'That is one of the accepted answers.',
} as const

const ERROR_LABELS: Record<string, string> = {
  WRONG_CASE: 'Case',
  WRONG_ARTICLE: 'Article',
  WRONG_ENDING: 'Ending',
  VERB_FORM: 'Verb form',
  TENSE: 'Tense',
  WORD_ORDER: 'Word order',
  PREPOSITION: 'Preposition',
  WRONG_WORD: 'Word choice',
  SPELLING: 'Spelling',
  MISSING_WORD: 'Missing word',
  EXTRA_WORD: 'Extra word',
  MEANING: 'Meaning',
  OTHER: 'Other',
}

export const errorLabel = (type: string) => ERROR_LABELS[type] ?? 'Other'

/** "80%" for a 0–1 score. */
export const formatScore = (score: number) => `${Math.round(score * 100)}%`

/** Inserts {@code text} into an input at its cursor, as the umlaut keys do. */
export function insertAt(
  value: string,
  start: number | null,
  end: number | null,
  text: string,
): { value: string; cursor: number } {
  const from = start ?? value.length
  const to = end ?? from
  return {
    value: value.slice(0, from) + text + value.slice(to),
    cursor: from + text.length,
  }
}
