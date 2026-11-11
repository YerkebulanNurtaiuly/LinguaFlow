import { z } from 'zod'
import type {
  CreateVocabularyRequest,
  UpdateVocabularyRequest,
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

export const GENDERS = ['MASCULINE', 'FEMININE', 'NEUTER'] as const

export type PartOfSpeech = (typeof PARTS_OF_SPEECH)[number]
export type KnowledgeStatus = (typeof KNOWLEDGE_STATUSES)[number]
export type AiStatus = (typeof AI_STATUSES)[number]
export type Gender = (typeof GENDERS)[number]

/** Optional in the API and sent as null; undefined on the client. */
const optional = <T extends z.ZodType>(schema: T) =>
  schema.nullish().transform((value) => value ?? undefined)

const vocabularyItemSchema = z.object({
  id: z.string(),
  lemma: z.string(),
  article: optional(z.string()),
  partOfSpeech: optional(z.enum(PARTS_OF_SPEECH)),
  translation: optional(z.string()),
  gender: optional(z.enum(GENDERS)),
  plural: optional(z.string()),
  cefrLevel: optional(z.enum(CEFR_LEVELS)),
  status: z.enum(KNOWLEDGE_STATUSES),
  aiStatus: z.enum(AI_STATUSES),
  // Field names whose current value came from AI (VOC-05)
  aiGeneratedFields: z.array(z.string()),
  examples: z.array(
    z.object({
      id: z.string(),
      sentence: z.string(),
      translation: optional(z.string()),
      aiGenerated: z.boolean(),
    }),
  ),
  notes: optional(z.string()),
  // Word-type data, e.g. a verb's principal parts (VOC-05)
  extra: z.record(z.string(), z.unknown()).optional(),
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
  /** Only words of this study session (SES-01) */
  sessionId?: string
  sort: VocabularySort
  page: number
}

export function toSearchParams(
  query: VocabularyQuery,
  size = PAGE_SIZE,
): URLSearchParams {
  const params = new URLSearchParams()
  if (query.q) params.set('q', query.q)
  if (query.status) params.set('status', query.status)
  if (query.cefrLevel) params.set('cefrLevel', query.cefrLevel)
  if (query.partOfSpeech) params.set('partOfSpeech', query.partOfSpeech)
  if (query.tagId) params.set('tagId', query.tagId)
  if (query.sessionId) params.set('sessionId', query.sessionId)
  params.set('sort', query.sort)
  // Ties (same date, same level…) are broken by id on the server, so pages are stable
  params.set('page', String(query.page))
  params.set('size', String(size))
  return params
}

/** One page of the learner's words in their active language. */
export async function fetchVocabulary(
  query: VocabularyQuery,
  signal?: AbortSignal,
  size = PAGE_SIZE,
): Promise<VocabularyWordPage> {
  const { data } = await api.get<unknown>('/vocabulary', {
    params: toSearchParams(query, size),
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

export type VocabularyExample = VocabularyWord['examples'][number]

export async function fetchVocabularyItem(id: string): Promise<VocabularyWord> {
  const { data } = await api.get<unknown>(
    `/vocabulary/${encodeURIComponent(id)}`,
  )
  return vocabularyItemSchema.parse(data)
}

/**
 * Adds a word to the active language. A word the learner already has is rejected
 * with 409 VOCABULARY_DUPLICATE and the existing word's `existingId`.
 */
export async function createVocabularyItem(
  request: CreateVocabularyRequest,
): Promise<VocabularyWord> {
  const { data } = await api.post<unknown>('/vocabulary', request)
  return vocabularyItemSchema.parse(data)
}

/**
 * Partial update: absent fields stay unchanged, an empty string clears translation,
 * plural or notes, and `tagIds` replaces all tags.
 */
export async function updateVocabularyItem(
  id: string,
  request: UpdateVocabularyRequest,
): Promise<VocabularyWord> {
  const { data } = await api.patch<unknown>(
    `/vocabulary/${encodeURIComponent(id)}`,
    request,
  )
  return vocabularyItemSchema.parse(data)
}

/** Creates a tag in the active language; a taken name is 409 TAG_NAME_TAKEN. */
export async function createTag(name: string): Promise<TagWithCount> {
  const { data } = await api.post<unknown>('/tags', { name })
  return tagSchema.parse(data)
}

export const AUXILIARIES = ['HABEN', 'SEIN'] as const
export type Auxiliary = (typeof AUXILIARIES)[number]

export interface VerbForms {
  praeteritum: string
  partizip2: string
  auxiliary: Auxiliary
}

/** A verb's principal parts, stored in `extra` (VOC-05), if it has them. */
export function verbFormsOf(word: VocabularyWord): VerbForms | undefined {
  const extra = word.extra ?? {}
  const { praeteritum, partizip2, auxiliary } = extra
  if (typeof praeteritum !== 'string' || typeof partizip2 !== 'string') {
    return undefined
  }
  return {
    praeteritum,
    partizip2,
    // Older data (the dev seed) writes the auxiliary in lower case
    auxiliary: String(auxiliary).toUpperCase() === 'HABEN' ? 'HABEN' : 'SEIN',
  }
}

const regenerateSchema = z.object({
  item: vocabularyItemSchema,
  aiJobId: z.string(),
})

/**
 * Asks the AI again (VOC-06): it clears what the AI filled in and its examples, keeps
 * the learner's own values, and queues a fresh answer. 409 CONFLICT while the AI is
 * still working on the word.
 */
export async function regenerateVocabularyItem(
  id: string,
): Promise<z.infer<typeof regenerateSchema>> {
  const { data } = await api.post<unknown>(
    `/vocabulary/${encodeURIComponent(id)}/regenerate`,
  )
  return regenerateSchema.parse(data)
}
