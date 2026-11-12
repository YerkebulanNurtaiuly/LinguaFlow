import { z } from 'zod'
import type {
  Flashcard as ApiFlashcard,
  ReviewQueue as ApiReviewQueue,
  ReviewResult as ApiReviewResult,
  ReviewStats as ApiReviewStats,
  SubmitReviewRequest,
} from '@/api/types'
import { api } from '@/lib/api'

const optional = <T extends z.ZodType>(schema: T) =>
  schema.nullish().transform((value) => value ?? undefined)

const srsStates = ['NEW', 'LEARNING', 'REVIEW', 'RELEARNING'] as const
export const REVIEW_GRADES = ['AGAIN', 'HARD', 'GOOD', 'EASY'] as const
export type ReviewGrade = (typeof REVIEW_GRADES)[number]

export const flashcardSchema = z.object({
  id: z.string(),
  vocabularyItemId: z.string(),
  lemma: z.string(),
  direction: z.enum(['TARGET_TO_NATIVE', 'NATIVE_TO_TARGET']),
  suspended: z.boolean(),
  content: z.object({
    front: z.string(),
    hint: optional(z.string()),
    back: z.string(),
    plural: optional(z.string()),
    praeteritum: optional(z.string()),
    perfect: optional(z.string()),
    examples: z.array(
      z.object({ sentence: z.string(), translation: optional(z.string()) }),
    ),
    complete: z.boolean(),
  }),
  srs: z.object({
    state: z.enum(srsStates),
    dueAt: optional(z.string()),
    intervalDays: z.number(),
    repetitions: z.number(),
    lapses: z.number(),
    lastReviewedAt: optional(z.string()),
  }),
  createdAt: z.string(),
  updatedAt: z.string(),
}) satisfies z.ZodType<ApiFlashcard>

const previewSchema = z.object({
  state: z.enum(srsStates),
  intervalDays: z.number(),
  dueAt: z.string(),
})

/** A card with where each grade would send it (also used by practice sessions). */
export const queueItemSchema = z.object({
  card: flashcardSchema,
  previews: z.object({
    again: previewSchema,
    hard: previewSchema,
    good: previewSchema,
    easy: previewSchema,
  }),
})

const queueSchema = z.object({
  cards: z.array(queueItemSchema),
  dueCount: z.number(),
  newCount: z.number(),
  today: z.string(),
  reviewedToday: z.number(),
  dailyReviewGoal: z.number(),
  newCardsToday: z.number(),
  newCardsPerDay: z.number(),
  nextLearningDueAt: optional(z.string()),
}) satisfies z.ZodType<ApiReviewQueue>

export const reviewSchema = z.object({
  id: z.string(),
  flashcardId: z.string(),
  grade: z.enum(REVIEW_GRADES),
  reviewedAt: z.string(),
  durationMs: optional(z.number()),
  stateBefore: z.enum(srsStates),
  intervalBefore: z.number(),
  intervalAfter: z.number(),
  easeBefore: z.number(),
  easeAfter: z.number(),
})

const resultSchema = z.object({
  review: reviewSchema,
  card: flashcardSchema,
  replayed: z.boolean(),
}) satisfies z.ZodType<ApiReviewResult>

const statsSchema = z.object({
  today: z.string(),
  dueToday: z.number(),
  newAvailable: z.number(),
  reviewedToday: z.number(),
  dailyReviewGoal: z.number(),
  currentStreak: z.number(),
  longestStreak: z.number(),
  retention: z.object({
    days: z.number(),
    reviews: z.number(),
    remembered: z.number(),
    rate: optional(z.number()),
  }),
  forecast: z.array(z.object({ date: z.string(), due: z.number() })),
}) satisfies z.ZodType<ApiReviewStats>

export type Flashcard = z.infer<typeof flashcardSchema>
export type GradePreview = z.infer<typeof previewSchema>
export type ReviewQueue = z.infer<typeof queueSchema>
export type QueueItem = ReviewQueue['cards'][number]
export type FlashcardReview = z.infer<typeof reviewSchema>
export const SRS_STATES = srsStates
export type SrsState = (typeof srsStates)[number]
export type ReviewResult = z.infer<typeof resultSchema>
export type ReviewStats = z.infer<typeof statsSchema>

/** The most cards one queue request may return (SRS-02). */
export const QUEUE_LIMIT = 50

/** Cards to review now, in order: overdue, due today, then new (SRS-02). */
export async function fetchReviewQueue(
  limit = QUEUE_LIMIT,
): Promise<ReviewQueue> {
  const { data } = await api.get<unknown>('/reviews/due', {
    params: { limit },
  })
  return queueSchema.parse(data)
}

/** Due today, streaks, retention and the 7-day forecast (SRS-03). */
export async function fetchReviewStats(): Promise<ReviewStats> {
  const { data } = await api.get<unknown>('/reviews/stats')
  return statsSchema.parse(data)
}

/**
 * Records a grade. Resending the same `reviewToken` changes nothing on the server, so a
 * retry after a network error keeps the card's token.
 */
export async function submitReview(
  request: SubmitReviewRequest,
): Promise<ReviewResult> {
  const { data } = await api.post<unknown>('/reviews', request)
  return resultSchema.parse(data)
}
