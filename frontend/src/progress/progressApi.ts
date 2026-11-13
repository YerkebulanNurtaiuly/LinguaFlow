import { z } from 'zod'
import type {
  ProgressStats as ApiProgressStats,
  WeakPoints as ApiWeakPoints,
  WeakSpotsStarted as ApiWeakSpotsStarted,
} from '@/api/types'
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
