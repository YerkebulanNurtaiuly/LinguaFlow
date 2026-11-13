import { z } from 'zod'
import type {
  FrequentMistakes as ApiFrequentMistakes,
  ProgressStats as ApiProgressStats,
  WeakPoints as ApiWeakPoints,
  WeakSpotsStarted as ApiWeakSpotsStarted,
} from '@/api/types'
import { EXERCISE_TYPES } from '@/exercises/exercisesApi'
import { api } from '@/lib/api'

const optional = <T extends z.ZodType>(schema: T) =>
  schema.nullish().transform((value) => value ?? undefined)

const statuses = ['NEW', 'LEARNING', 'LEARNED', 'MASTERED'] as const
const levels = ['A1', 'A2', 'B1', 'B2', 'C1', 'C2'] as const

const materialSchema = z.object({
  total: z.number(),
  byStatus: z.array(z.object({ status: z.enum(statuses), count: z.number() })),
  byLevel: z.array(z.object({ level: z.enum(levels), count: z.number() })),
  unleveled: z.number(),
})

const statsSchema = z.object({
  today: z.string(),
  words: materialSchema,
  grammarTopics: materialSchema,
  activity: z.array(
    z.object({
      date: z.string(),
      reviews: z.number(),
      remembered: z.number(),
      exercises: z.number(),
      studiedMs: z.number(),
    }),
  ),
  accuracy: z.array(
    z.object({
      weekStart: z.string(),
      reviews: z.number(),
      remembered: z.number(),
      reviewRate: optional(z.number()),
      exercises: z.number(),
      exerciseScore: optional(z.number()),
    }),
  ),
  currentStreak: z.number(),
  longestStreak: z.number(),
  timeStudied: z.object({
    todayMs: z.number(),
    periodMs: z.number(),
    totalMs: z.number(),
  }),
}) satisfies z.ZodType<ApiProgressStats>
export type ProgressStats = z.infer<typeof statsSchema>

export const REASON_CODES = [
  'LOW_ACCURACY',
  'FREQUENT_MISTAKE',
  'LAPSES',
  'NOT_PRACTISED_RECENTLY',
] as const

const weakPointSchema = z.object({
  id: z.string(),
  name: z.string(),
  translation: optional(z.string()),
  status: z.enum(statuses),
  score: z.number(),
  answers: z.number(),
  recentAccuracy: optional(z.number()),
  lastPracticedAt: optional(z.string()),
  reasons: z.array(
    z.object({
      code: z.enum(REASON_CODES),
      value: z.number(),
      errorType: optional(z.string()),
    }),
  ),
})
export type WeakPoint = z.infer<typeof weakPointSchema>
export type WeakPointReason = WeakPoint['reasons'][number]

const weakPointsSchema = z.object({
  grammarTopics: z.array(weakPointSchema),
  words: z.array(weakPointSchema),
}) satisfies z.ZodType<ApiWeakPoints>
export type WeakPoints = z.infer<typeof weakPointsSchema>

const weakSpotsSchema = z.object({
  exerciseSetId: z.string(),
  aiJobId: z.string(),
  grammarTopics: z.array(weakPointSchema),
  words: z.number(),
  focusMistakes: z.array(z.string()),
}) satisfies z.ZodType<ApiWeakSpotsStarted>
export type WeakSpotsStarted = z.infer<typeof weakSpotsSchema>

/** Dashboard statistics (PRG-05); {@code days} is the activity heatmap's length. */
export async function fetchProgressStats(
  days = 7,
  weeks = 4,
): Promise<ProgressStats> {
  const { data } = await api.get('/progress/stats', { params: { days, weeks } })
  return statsSchema.parse(data)
}

export async function fetchWeakPoints(limit = 5): Promise<WeakPoints> {
  const { data } = await api.get('/progress/weak-points', {
    params: { limit },
  })
  return weakPointsSchema.parse(data)
}

/** "Practise my weak spots" (PRG-06): an exercise set being written. */
export async function startWeakSpots(): Promise<WeakSpotsStarted> {
  const { data } = await api.post('/progress/weak-spots/exercises', {})
  return weakSpotsSchema.parse(data)
}

const topicSchema = z.object({ id: z.string(), title: z.string() })

const mistakeGroupSchema = z.object({
  errorType: optional(z.string()),
  grammarTopic: optional(topicSchema),
  count: z.number(),
  lastSeenAt: z.string(),
  breakdown: z.array(
    z.object({
      errorType: optional(z.string()),
      grammarTopic: optional(topicSchema),
      count: z.number(),
    }),
  ),
  examples: z.array(
    z.object({
      mistakeId: z.string(),
      errorType: z.string(),
      excerpt: optional(z.string()),
      explanation: optional(z.string()),
      madeAt: z.string(),
      grammarTopic: optional(topicSchema),
      attempt: optional(
        z.object({
          exerciseId: z.string(),
          exerciseType: z.enum(EXERCISE_TYPES),
          exercise: z.string(),
          answer: z.string(),
          correctAnswer: z.string(),
        }),
      ),
    }),
  ),
})
export type MistakeGroup = z.infer<typeof mistakeGroupSchema>

const mistakesSchema = z.object({
  groupBy: z.enum(['TYPE', 'TOPIC']),
  since: optional(z.string()),
  total: z.number(),
  groups: z.array(mistakeGroupSchema),
}) satisfies z.ZodType<ApiFrequentMistakes>
export type FrequentMistakes = z.infer<typeof mistakesSchema>
export type MistakeGrouping = FrequentMistakes['groupBy']

/** Frequent mistakes (PRG-04) over the last {@code days} days. */
export async function fetchFrequentMistakes(
  groupBy: MistakeGrouping,
  days: number,
): Promise<FrequentMistakes> {
  const { data } = await api.get('/progress/mistakes', {
    params: { groupBy, days, limit: 10, examples: 2 },
  })
  return mistakesSchema.parse(data)
}
