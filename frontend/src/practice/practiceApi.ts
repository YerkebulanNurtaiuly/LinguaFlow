import { z } from 'zod'
import type {
  PracticeOverview as ApiPracticeOverview,
  PracticeSession as ApiPracticeSession,
  PracticeSessionStarted as ApiPracticeSessionStarted,
  StartPracticeSessionRequest,
} from '@/api/types'
import { exerciseSchema } from '@/exercises/exercisesApi'
import { api } from '@/lib/api'
import { queueItemSchema } from '@/reviews/reviewsApi'

const optional = <T extends z.ZodType>(schema: T) =>
  schema.nullish().transform((value) => value ?? undefined)

export const PRACTICE_MODES = [
  'CURRENT_LESSON',
  'MIXED',
  'FULL_REVISION',
  'WEAK_SPOTS',
] as const
export type PracticeMode = (typeof PRACTICE_MODES)[number]

const statuses = ['IN_PROGRESS', 'COMPLETED', 'ABANDONED'] as const

const summarySchema = z.object({
  items: z.number(),
  answeredItems: z.number(),
  flashcards: z.object({
    items: z.number(),
    answered: z.number(),
    remembered: z.number(),
    again: z.number(),
    hard: z.number(),
    good: z.number(),
    easy: z.number(),
  }),
  exercises: z.object({
    items: z.number(),
    answered: z.number(),
    correct: z.number(),
    averageScore: optional(z.number()),
  }),
  durationMs: z.number(),
  missedFlashcardIds: z.array(z.string()),
  missedExerciseIds: z.array(z.string()),
})

const itemSchema = z.object({
  position: z.number(),
  type: z.enum(['FLASHCARD', 'EXERCISE']),
  answeredAt: optional(z.string()),
  flashcard: optional(queueItemSchema),
  exercise: optional(exerciseSchema),
})

const sessionSchema = z.object({
  id: z.string(),
  languageCode: z.string(),
  mode: z.enum(PRACTICE_MODES),
  status: z.enum(statuses),
  studySessionId: optional(z.string()),
  config: z.object({
    length: z.number(),
    includeFlashcards: z.boolean(),
    includeExercises: z.boolean(),
    newRatio: z.number(),
    seed: z.number(),
  }),
  startedAt: z.string(),
  completedAt: optional(z.string()),
  items: z.array(itemSchema),
  exerciseSet: optional(
    z.object({
      id: z.string(),
      status: z.enum(['GENERATING', 'READY', 'FAILED']),
      requested: z.number(),
    }),
  ),
  summary: optional(summarySchema),
}) satisfies z.ZodType<ApiPracticeSession>
export type PracticeSession = z.infer<typeof sessionSchema>
export type PracticeItem = PracticeSession['items'][number]
export type PracticeSummary = z.infer<typeof summarySchema>

const startedSchema = z.object({
  practiceSession: sessionSchema,
  aiJobId: optional(z.string()),
}) satisfies z.ZodType<ApiPracticeSessionStarted>

const listedSchema = z.object({
  id: z.string(),
  mode: z.enum(PRACTICE_MODES),
  status: z.enum(statuses),
  studySessionId: optional(z.string()),
  startedAt: z.string(),
  completedAt: optional(z.string()),
  items: z.number(),
  answered: z.number(),
})

const overviewSchema = z.object({
  currentLesson: optional(
    z.object({
      id: z.string(),
      title: z.string(),
      studiedOn: z.string(),
      words: z.number(),
      topics: z.number(),
    }),
  ),
  words: z.number(),
  freshWords: z.number(),
  topics: z.number(),
  practiceSessionLength: z.number(),
  inProgress: optional(listedSchema),
}) satisfies z.ZodType<ApiPracticeOverview>
export type PracticeOverview = z.infer<typeof overviewSchema>

export type ListedPracticeSession = z.infer<typeof listedSchema>

const listedPageSchema = z.object({ content: z.array(listedSchema) })

/** The latest practice sessions, newest first. */
export async function fetchRecentPracticeSessions(
  size = 5,
): Promise<ListedPracticeSession[]> {
  const { data } = await api.get<unknown>('/practice-sessions', {
    params: { size },
  })
  return listedPageSchema.parse(data).content
}

export async function fetchPracticeOverview(): Promise<PracticeOverview> {
  const { data } = await api.get<unknown>('/practice-sessions/overview')
  return overviewSchema.parse(data)
}

export interface NewPracticeSession {
  mode: PracticeMode
  length: number
  includeFlashcards: boolean
  includeExercises: boolean
}

export async function startPracticeSession(
  request: NewPracticeSession,
): Promise<PracticeSession> {
  const body: StartPracticeSessionRequest = request
  const { data } = await api.post<unknown>('/practice-sessions', body)
  return startedSchema.parse(data).practiceSession
}

export async function fetchPracticeSession(
  id: string,
): Promise<PracticeSession> {
  const { data } = await api.get<unknown>(`/practice-sessions/${id}`)
  return sessionSchema.parse(data)
}

export async function completePracticeSession(
  id: string,
): Promise<PracticeSession> {
  const { data } = await api.post<unknown>(`/practice-sessions/${id}/complete`)
  return sessionSchema.parse(data)
}

/** "Practise mistakes again": a new session with what this one got wrong. */
export async function retryPracticeSession(
  id: string,
): Promise<PracticeSession> {
  const { data } = await api.post<unknown>(`/practice-sessions/${id}/retry`)
  return startedSchema.parse(data).practiceSession
}
