import { CEFR_LEVELS } from '@/languages/languagesApi'
import {
  DEFAULT_SORT,
  KNOWLEDGE_STATUSES,
  PARTS_OF_SPEECH,
  VOCABULARY_SORTS,
  type PartOfSpeech,
  type VocabularyQuery,
  type VocabularySort,
} from '@/vocabulary/vocabularyApi'

const oneOf = <T extends string>(
  values: readonly T[],
  raw: string | null,
): T | undefined =>
  raw !== null && (values as readonly string[]).includes(raw)
    ? (raw as T)
    : undefined

/**
 * The list's state lives in the URL (?q=&status=&cefrLevel=&partOfSpeech=&tagId=&sort=&page=),
 * so a filtered view can be reloaded, bookmarked and navigated back to. `page` in the
 * URL is 1-based; unknown values are ignored.
 */
export function queryFromParams(params: URLSearchParams): VocabularyQuery {
  const page = Number.parseInt(params.get('page') ?? '', 10)
  return {
    q: params.get('q')?.trim() || undefined,
    status: oneOf(KNOWLEDGE_STATUSES, params.get('status')),
    cefrLevel: oneOf(CEFR_LEVELS, params.get('cefrLevel')),
    partOfSpeech: oneOf(PARTS_OF_SPEECH, params.get('partOfSpeech')),
    tagId: params.get('tagId') || undefined,
    sort:
      oneOf(
        VOCABULARY_SORTS.map((option) => option.value),
        params.get('sort'),
      ) ?? DEFAULT_SORT,
    page: Number.isFinite(page) && page > 1 ? page - 1 : 0,
  }
}

/** The URL for a query; defaults are left out to keep it short. */
export function paramsFromQuery(query: VocabularyQuery): URLSearchParams {
  const params = new URLSearchParams()
  if (query.q) params.set('q', query.q)
  if (query.status) params.set('status', query.status)
  if (query.cefrLevel) params.set('cefrLevel', query.cefrLevel)
  if (query.partOfSpeech) params.set('partOfSpeech', query.partOfSpeech)
  if (query.tagId) params.set('tagId', query.tagId)
  if (query.sort !== DEFAULT_SORT) params.set('sort', query.sort)
  if (query.page > 0) params.set('page', String(query.page + 1))
  return params
}

export function hasFilters(query: VocabularyQuery): boolean {
  return Boolean(
    query.q ||
    query.status ||
    query.cefrLevel ||
    query.partOfSpeech ||
    query.tagId,
  )
}

export const PART_OF_SPEECH_LABELS: Record<PartOfSpeech, string> = {
  NOUN: 'Noun',
  VERB: 'Verb',
  ADJECTIVE: 'Adjective',
  ADVERB: 'Adverb',
  PRONOUN: 'Pronoun',
  PREPOSITION: 'Preposition',
  CONJUNCTION: 'Conjunction',
  ARTICLE: 'Article',
  NUMERAL: 'Numeral',
  INTERJECTION: 'Interjection',
  PHRASE: 'Phrase',
  OTHER: 'Other',
}

export type { VocabularySort }
