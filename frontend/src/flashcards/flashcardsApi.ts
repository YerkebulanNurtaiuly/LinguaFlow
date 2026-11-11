import { z } from 'zod'
import { api } from '@/lib/api'
import {
  flashcardSchema,
  reviewSchema,
  type Flashcard,
  type FlashcardReview,
  type SrsState,
} from '@/reviews/reviewsApi'

export const CARD_DIRECTIONS = ['TARGET_TO_NATIVE', 'NATIVE_TO_TARGET'] as const
export type CardDirection = (typeof CARD_DIRECTIONS)[number]

/** API sort values the browser offers; the first is the default. */
export const FLASHCARD_SORTS = ['createdAt,desc', 'dueAt,asc'] as const
export type FlashcardSort = (typeof FLASHCARD_SORTS)[number]

export const PAGE_SIZE = 20

/** What the browser shows; `page` is zero-based like the API. */
export interface FlashcardQuery {
  q?: string
  direction?: CardDirection
  state?: SrsState
  suspended?: boolean
  sort: FlashcardSort
  page: number
}

const pageOf = <T extends z.ZodType>(item: T) =>
  z.object({
    content: z.array(item),
    page: z.number(),
    size: z.number(),
    totalElements: z.number(),
    totalPages: z.number(),
  })

const cardPageSchema = pageOf(flashcardSchema)
const reviewPageSchema = pageOf(reviewSchema)

export type FlashcardPage = z.infer<typeof cardPageSchema>
export type FlashcardReviewPage = z.infer<typeof reviewPageSchema>
export type { Flashcard, FlashcardReview }

/** One page of the active language's flashcards (FC-02). */
export async function fetchFlashcards(
  query: FlashcardQuery,
  signal?: AbortSignal,
): Promise<FlashcardPage> {
  const params = new URLSearchParams()
  if (query.q) params.set('q', query.q)
  if (query.direction) params.set('direction', query.direction)
  if (query.state) params.set('state', query.state)
  if (query.suspended !== undefined) {
    params.set('suspended', String(query.suspended))
  }
  params.set('sort', query.sort)
  params.set('page', String(query.page))
  params.set('size', String(PAGE_SIZE))
  const { data } = await api.get<unknown>('/flashcards', { params, signal })
  return cardPageSchema.parse(data)
}

/** A card's latest reviews, newest first (at most 100). */
export async function fetchCardReviews(
  id: string,
  signal?: AbortSignal,
): Promise<FlashcardReviewPage> {
  const { data } = await api.get<unknown>(
    `/flashcards/${encodeURIComponent(id)}/reviews`,
    { params: { size: 100 }, signal },
  )
  return reviewPageSchema.parse(data)
}

/** Leaves a card out of reviews, or brings it back; its review state is kept. */
export async function setCardSuspended(
  id: string,
  suspended: boolean,
): Promise<Flashcard> {
  const { data } = await api.patch<unknown>(
    `/flashcards/${encodeURIComponent(id)}`,
    { suspended },
  )
  return flashcardSchema.parse(data)
}
