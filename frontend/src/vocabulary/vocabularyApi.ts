import { z } from 'zod'
import type {
  VocabularyItem as ApiVocabularyItem,
  VocabularyPage as ApiVocabularyPage,
  VocabularyTagSummary,
} from '@/api/types'
import { api } from '@/lib/api'
import { CEFR_LEVELS } from '@/languages/languagesApi'

export const PARTS_OF_SPEECH = [
  'NOUN',
  'VERB',
  'ADJECTIVE',
  'ADVERB',
  'PRONOUN',
  'PREPOSITION',
  'CONJUNCTION',
  'ARTICLE',
  'NUMERAL',
  'INTERJECTION',
  'PHRASE',
  'OTHER',
] as const

export const KNOWLEDGE_STATUSES = [
  'NEW',
  'LEARNING',
  'LEARNED',
  'MASTERED',
] as const

export const AI_STATUSES = ['PENDING_AI', 'READY', 'AI_FAILED'] as const

export type PartOfSpeech = (typeof PARTS_OF_SPEECH)[number]
export type KnowledgeStatus = (typeof KNOWLEDGE_STATUSES)[number]
export type AiStatus = (typeof AI_STATUSES)[number]

/** Optional in the API and sent as null; undefined on the client. */
const optional = <T extends z.ZodType>(schema: T) =>
  schema.nullish().transform((value) => value ?? undefined)

const vocabularyItemSchema = z.object({
  id: z.string(),
  lemma: z.string(),
  article: optional(z.string()),
  partOfSpeech: optional(z.enum(PARTS_OF_SPEECH)),
  translation: optional(z.string()),
  plural: optional(z.string()),
  cefrLevel: optional(z.enum(CEFR_LEVELS)),
  status: z.enum(KNOWLEDGE_STATUSES),
  aiStatus: z.enum(AI_STATUSES),
  notes: optional(z.string()),
  tags: z.array(z.object({ id: z.string(), name: z.string() })),
  createdAt: z.string(),
  updatedAt: z.string(),
  version: z.number(),
}) satisfies z.ZodType<ApiVocabularyItem>

const vocabularyPageSchema = z.object({
  content: z.array(vocabularyItemSchema),
  page: z.number(),
  size: z.number(),
  totalElements: z.number(),
  totalPages: z.number(),
}) satisfies z.ZodType<ApiVocabularyPage>

const tagSchema = z.object({
  id: z.string(),
  name: z.string(),
  wordCount: z.number(),
}) satisfies z.ZodType<VocabularyTagSummary>

export type VocabularyWord = z.infer<typeof vocabularyItemSchema>
export type VocabularyWordPage = z.infer<typeof vocabularyPageSchema>
export type TagWithCount = z.infer<typeof tagSchema>

/** Sort options of the list, as the API's `sort` parameter. */
export const VOCABULARY_SORTS = [
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'createdAt,asc', label: 'Oldest first' },
  { value: 'lemma,asc', label: 'Word (A–Z)' },
  { value: 'lemma,desc', label: 'Word (Z–A)' },
  { value: 'updatedAt,desc', label: 'Recently changed' },
  { value: 'cefrLevel,asc', label: 'Level (A1 first)' },
  { value: 'status,asc', label: 'Status' },
] as const

export type VocabularySort = (typeof VOCABULARY_SORTS)[number]['value']

export const DEFAULT_SORT: VocabularySort = 'createdAt,desc'

export const PAGE_SIZE = 20

/** What the list shows; `page` is zero-based like the API. */
export interface VocabularyQuery {
  q?: string
  status?: KnowledgeStatus
  cefrLevel?: (typeof CEFR_LEVELS)[number]
  partOfSpeech?: PartOfSpeech
  tagId?: string
  sort: VocabularySort
  page: number
}

export function toSearchParams(query: VocabularyQuery): URLSearchParams {
  const params = new URLSearchParams()
  if (query.q) params.set('q', query.q)
  if (query.status) params.set('status', query.status)
  if (query.cefrLevel) params.set('cefrLevel', query.cefrLevel)
  if (query.partOfSpeech) params.set('partOfSpeech', query.partOfSpeech)
  if (query.tagId) params.set('tagId', query.tagId)
  params.set('sort', query.sort)
  // Ties (same date, same level…) are broken by id on the server, so pages are stable
  params.set('page', String(query.page))
  params.set('size', String(PAGE_SIZE))
  return params
}

/** One page of the learner's words in their active language. */
export async function fetchVocabulary(
  query: VocabularyQuery,
  signal?: AbortSignal,
): Promise<VocabularyWordPage> {
  const { data } = await api.get<unknown>('/vocabulary', {
    params: toSearchParams(query),
    signal,
  })
  return vocabularyPageSchema.parse(data)
}

/** Deletes the word with its examples, flashcards and progress. */
export async function deleteVocabularyItem(id: string): Promise<void> {
  await api.delete(`/vocabulary/${encodeURIComponent(id)}`)
}

/** The learner's tags in the active language, by name, with word counts. */
export async function fetchTags(): Promise<TagWithCount[]> {
  const { data } = await api.get<unknown>('/tags')
  return z.array(tagSchema).parse(data)
}
