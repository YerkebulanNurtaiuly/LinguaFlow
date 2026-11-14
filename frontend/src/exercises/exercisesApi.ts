import { z } from 'zod'
import type {
  ExerciseAttemptResult as ApiAttemptResult,
  CreateExerciseSetRequest,
  ExerciseSet as ApiExerciseSet,
  MistakeExplanation as ApiMistakeExplanation,
  SubmitAttemptRequest,
} from '@/api/types'
import { api } from '@/lib/api'

const optional = <T extends z.ZodType>(schema: T) =>
  schema.nullish().transform((value) => value ?? undefined)

const instruction = optional(z.string())

const fillBlank = z.object({
  instruction,
  text: z.string(),
  hint: optional(z.string()),
})
const multipleChoice = z.object({
  instruction,
  question: z.string(),
  options: z.array(z.string()),
})
const translation = z.object({
  instruction,
  direction: z.enum(['TARGET_TO_NATIVE', 'NATIVE_TO_TARGET']),
  text: z.string(),
})
const sentenceConstruction = z.object({
  instruction,
  task: z.string(),
  words: z.array(z.string()),
})
const errorCorrection = z.object({ instruction, sentence: z.string() })
const wordOrder = z.object({ instruction, tokens: z.array(z.string()) })
const transformation = z.object({
  instruction,
  sentence: z.string(),
  task: z.string(),
})

export const EXERCISE_TYPES = [
  'FILL_BLANK',
  'MULTIPLE_CHOICE',
  'TRANSLATION',
  'SENTENCE_CONSTRUCTION',
  'ERROR_CORRECTION',
  'WORD_ORDER',
  'TRANSFORMATION',
] as const

const common = {
  id: z.string(),
  position: z.number(),
  difficulty: optional(z.enum(['EASY', 'MEDIUM', 'HARD'])),
  grammarTopicIds: z.array(z.string()),
  vocabularyItemIds: z.array(z.string()),
  reported: z.boolean(),
}

/** One exercise, its prompt typed by its type; solutions never come with it. */
export const exerciseSchema = z.discriminatedUnion('type', [
  z.object({ ...common, type: z.literal('FILL_BLANK'), prompt: fillBlank }),
  z.object({
    ...common,
    type: z.literal('MULTIPLE_CHOICE'),
    prompt: multipleChoice,
  }),
  z.object({ ...common, type: z.literal('TRANSLATION'), prompt: translation }),
  z.object({
    ...common,
    type: z.literal('SENTENCE_CONSTRUCTION'),
    prompt: sentenceConstruction,
  }),
  z.object({
    ...common,
    type: z.literal('ERROR_CORRECTION'),
    prompt: errorCorrection,
  }),
  z.object({ ...common, type: z.literal('WORD_ORDER'), prompt: wordOrder }),
  z.object({
    ...common,
    type: z.literal('TRANSFORMATION'),
    prompt: transformation,
  }),
])
export type Exercise = z.infer<typeof exerciseSchema>
export type ExerciseType = Exercise['type']

const exerciseSetSchema = z.object({
  id: z.string(),
  languageCode: z.string(),
  source: z.enum(['MANUAL', 'PRACTICE', 'WEAK_SPOTS']),
  status: z.enum(['GENERATING', 'READY', 'FAILED']),
  request: z.object({
    grammarTopicIds: z.array(z.string()),
    types: z.array(z.enum(EXERCISE_TYPES)),
    count: z.number(),
    difficulty: optional(z.enum(['EASY', 'MEDIUM', 'HARD'])),
  }),
  exercises: z.array(exerciseSchema),
  createdAt: z.string(),
}) satisfies z.ZodType<ApiExerciseSet>
export type ExerciseSet = z.infer<typeof exerciseSetSchema>

export const ERROR_TYPES = [
  'WRONG_CASE',
  'WRONG_ARTICLE',
  'WRONG_ENDING',
  'VERB_FORM',
  'TENSE',
  'WORD_ORDER',
  'PREPOSITION',
  'WRONG_WORD',
  'SPELLING',
  'MISSING_WORD',
  'EXTRA_WORD',
  'MEANING',
  'OTHER',
] as const

const attemptResultSchema = z.object({
  attempt: z.object({
    id: z.string(),
    exerciseId: z.string(),
    correct: z.boolean(),
    score: z.number(),
    attemptedAt: z.string(),
  }),
  correct: z.boolean(),
  score: z.number(),
  evaluatedBy: z.enum(['RULES', 'AI']),
  notes: z.array(z.enum(['CAPITALIZATION', 'UMLAUT_SPELLING', 'ALTERNATIVE'])),
  parts: z.array(z.boolean()),
  correctAnswer: z.string(),
  alternatives: z.array(z.string()),
  explanation: optional(z.string()),
  feedback: optional(
    z.object({
      errors: z.array(
        z.object({
          type: z.enum(ERROR_TYPES),
          span: optional(z.string()),
          explanation: z.string(),
          grammarTopicId: optional(z.string()),
        }),
      ),
      correctedAnswer: optional(z.string()),
      explanation: z.string(),
    }),
  ),
  mistakes: z.array(
    z.object({
      id: z.string(),
      errorType: z.string(),
      excerpt: optional(z.string()),
    }),
  ),
}) satisfies z.ZodType<ApiAttemptResult>
export type AttemptResult = z.infer<typeof attemptResultSchema>

const explanationSchema = z.object({
  answerIsCorrect: z.boolean(),
  mistake: z.string(),
  rule: z.string(),
  topic: optional(z.string()),
  grammarTopicId: optional(z.string()),
  example: optional(
    z.object({ sentence: z.string(), translation: z.string() }),
  ),
  otherErrors: z.array(z.string()),
  cached: z.boolean(),
}) satisfies z.ZodType<ApiMistakeExplanation>
export type MistakeExplanation = z.infer<typeof explanationSchema>

/** The answer in the field the exercise type takes. */
export type ExerciseAnswer =
  | { blanks: string[] }
  | { index: number }
  | { tokens: string[] }
  | { text: string }

export async function fetchExerciseSet(id: string): Promise<ExerciseSet> {
  const { data } = await api.get<unknown>(`/exercise-sets/${id}`)
  return exerciseSetSchema.parse(data)
}

export async function submitAttempt(
  exerciseId: string,
  answer: ExerciseAnswer,
  durationMs?: number,
  practiceSessionId?: string,
): Promise<AttemptResult> {
  const body: SubmitAttemptRequest = {
    ...answer,
    durationMs,
    practiceSessionId,
  }
  const { data } = await api.post<unknown>(
    `/exercises/${exerciseId}/attempts`,
    body,
  )
  return attemptResultSchema.parse(data)
}

export async function explainAttempt(
  exerciseId: string,
  attemptId: string,
): Promise<MistakeExplanation> {
  const { data } = await api.post<unknown>(
    `/exercises/${exerciseId}/attempts/${attemptId}/explanation`,
  )
  return explanationSchema.parse(data)
}

export async function reportExercise(
  exerciseId: string,
  reason: string | undefined,
): Promise<Exercise> {
  const { data } = await api.post<unknown>(`/exercises/${exerciseId}/report`, {
    reason: reason?.trim() || undefined,
  })
  return exerciseSchema.parse(data)
}

export async function withdrawReport(exerciseId: string): Promise<Exercise> {
  const { data } = await api.delete<unknown>(`/exercises/${exerciseId}/report`)
  return exerciseSchema.parse(data)
}

export const EXERCISE_DIFFICULTIES = ['EASY', 'MEDIUM', 'HARD'] as const
export type ExerciseDifficulty = (typeof EXERCISE_DIFFICULTIES)[number]

/** What the learner asks for; empty types means their preferred types, or else all. */
export interface NewExerciseSet {
  grammarTopicIds: string[]
  types: ExerciseType[]
  count: number
  difficulty?: ExerciseDifficulty
}

/** Asks the AI for a set; it comes back GENERATING with the job writing it. */
export async function createExerciseSet(
  request: NewExerciseSet,
): Promise<{ exerciseSet: ExerciseSet; aiJobId: string }> {
  const body: CreateExerciseSetRequest = request
  const { data } = await api.post<unknown>('/exercise-sets', body)
  return z
    .object({ exerciseSet: exerciseSetSchema, aiJobId: z.string() })
    .parse(data)
}

const summarySchema = z.object({
  id: z.string(),
  status: z.enum(['GENERATING', 'READY', 'FAILED']),
  request: exerciseSetSchema.shape.request,
  exerciseCount: z.number(),
  createdAt: z.string(),
})
export type ExerciseSetSummary = z.infer<typeof summarySchema>

const summaryPageSchema = z.object({
  content: z.array(summarySchema),
  totalElements: z.number(),
})

/** The latest sets of the active language, newest first. */
export async function fetchExerciseSets(
  size = 10,
): Promise<z.infer<typeof summaryPageSchema>> {
  const { data } = await api.get<unknown>('/exercise-sets', {
    params: { page: 0, size },
  })
  return summaryPageSchema.parse(data)
}
