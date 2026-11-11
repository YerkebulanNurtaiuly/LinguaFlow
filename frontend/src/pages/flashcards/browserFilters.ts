import {
  CARD_DIRECTIONS,
  FLASHCARD_SORTS,
  type FlashcardQuery,
} from '@/flashcards/flashcardsApi'
import { SRS_STATES, type SrsState } from '@/reviews/reviewsApi'

export const DIRECTION_LABELS = {
  TARGET_TO_NATIVE: 'German → English',
  NATIVE_TO_TARGET: 'English → German',
} as const

export const STATE_LABELS: Record<SrsState, string> = {
  NEW: 'New',
  LEARNING: 'Learning',
  REVIEW: 'Review',
  RELEARNING: 'Relearning',
}

/** URL names for the sorts: `?sort=due`; the default (newest) is left out. */
const SORT_PARAMS: Record<FlashcardQuery['sort'], string> = {
  'createdAt,desc': 'newest',
  'dueAt,asc': 'due',
}

const oneOf = <T extends string>(
  values: readonly T[],
  raw: string | null,
): T | undefined =>
  raw !== null && (values as readonly string[]).includes(raw)
    ? (raw as T)
    : undefined

/**
 * The browser's state lives in the URL (?q=&direction=&state=&suspended=yes|no&sort=due&page=),
 * so a view can be reloaded and navigated back to. `page` is 1-based there; unknown values
 * are ignored.
 */
export function queryFromParams(params: URLSearchParams): FlashcardQuery {
  const page = Number.parseInt(params.get('page') ?? '', 10)
  const suspended = params.get('suspended')
  const sort = FLASHCARD_SORTS.find(
    (value) => SORT_PARAMS[value] === params.get('sort'),
  )
  return {
    q: params.get('q')?.trim() || undefined,
    direction: oneOf(CARD_DIRECTIONS, params.get('direction')),
    state: oneOf(SRS_STATES, params.get('state')),
    suspended:
      suspended === 'yes' ? true : suspended === 'no' ? false : undefined,
    sort: sort ?? FLASHCARD_SORTS[0],
    page: Number.isFinite(page) && page > 1 ? page - 1 : 0,
  }
}

export function paramsFromQuery(query: FlashcardQuery): URLSearchParams {
  const params = new URLSearchParams()
  if (query.q) params.set('q', query.q)
  if (query.direction) params.set('direction', query.direction)
  if (query.state) params.set('state', query.state)
  if (query.suspended !== undefined) {
    params.set('suspended', query.suspended ? 'yes' : 'no')
  }
  if (query.sort !== FLASHCARD_SORTS[0]) {
    params.set('sort', SORT_PARAMS[query.sort])
  }
  if (query.page > 0) params.set('page', String(query.page + 1))
  return params
}

export function hasFilters(query: FlashcardQuery): boolean {
  return Boolean(
    query.q || query.direction || query.state || query.suspended !== undefined,
  )
}

/** A due date relative to now: "Due now", "In 3 days", "Not started". */
export function formatDue(
  dueAt: string | undefined,
  state: SrsState,
  now: number,
): string {
  if (state === 'NEW' || !dueAt) return 'Not started'
  const ms = Date.parse(dueAt) - now
  if (ms <= 0) return 'Due now'
  const minutes = Math.round(ms / 60_000)
  if (minutes < 60) return `In ${minutes} min`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `In ${hours} h`
  const days = Math.round(hours / 24)
  return days === 1 ? 'Tomorrow' : `In ${days} days`
}
