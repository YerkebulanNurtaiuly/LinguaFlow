import { z } from 'zod'
import type {
  CreateGrammarTopicRequest,
  UpdateGrammarTopicRequest,
  GrammarTopicStatusChange as ApiStatusChange,
  GrammarCatalogEntry as ApiCatalogEntry,
  GrammarTopic as ApiGrammarTopic,
} from '@/api/types'
import { api } from '@/lib/api'
import { CEFR_LEVELS } from '@/languages/languagesApi'
import { KNOWLEDGE_STATUSES } from '@/vocabulary/vocabularyApi'

export type CefrLevel = (typeof CEFR_LEVELS)[number]
export type KnowledgeStatus = (typeof KNOWLEDGE_STATUSES)[number]

const optional = <T extends z.ZodType>(schema: T) =>
  schema.nullish().transform((value) => value ?? undefined)

const topicSchema = z.object({
  id: z.string(),
  title: z.string(),
  description: optional(z.string()),
  personalNotes: optional(z.string()),
  cefrLevel: optional(z.enum(CEFR_LEVELS)),
  status: z.enum(KNOWLEDGE_STATUSES),
  catalogTopicId: optional(z.string()),
  aiExplanation: optional(
    z.object({
      summary: optional(z.string()),
      rules: z.array(z.string()),
      examples: z.array(z.string()),
    }),
  ),
  aiExplanationGeneratedAt: optional(z.string()),
  createdAt: z.string(),
  updatedAt: z.string(),
  version: z.number(),
}) satisfies z.ZodType<ApiGrammarTopic>

const topicPageSchema = z.object({
  content: z.array(topicSchema),
  page: z.number(),
  totalPages: z.number(),
  totalElements: z.number(),
})

const catalogEntrySchema = z.object({
  id: z.string(),
  slug: z.string(),
  title: z.string(),
  description: z.string(),
  cefrLevel: z.enum(CEFR_LEVELS),
  topicId: optional(z.string()),
}) satisfies z.ZodType<ApiCatalogEntry>

export type GrammarTopic = z.infer<typeof topicSchema>
export type CatalogEntry = z.infer<typeof catalogEntrySchema>

export interface GrammarTopicFilter {
  q?: string
  status?: KnowledgeStatus
  cefrLevel?: CefrLevel
}

/** The API's page-size cap; all topics are loaded page by page. */
const PAGE_SIZE = 100

/**
 * All of the learner's topics in the active language that match `filter`, by level
 * then title. Learners have tens of topics, not thousands, so the page shows them all.
 */
export async function fetchGrammarTopics(
  filter: GrammarTopicFilter,
  signal?: AbortSignal,
): Promise<GrammarTopic[]> {
  const topics: GrammarTopic[] = []
  for (let page = 0; ; page++) {
    const params = new URLSearchParams()
    if (filter.q) params.set('q', filter.q)
    if (filter.status) params.set('status', filter.status)
    if (filter.cefrLevel) params.set('cefrLevel', filter.cefrLevel)
    params.append('sort', 'cefrLevel,asc')
    params.append('sort', 'title,asc')
    params.set('page', String(page))
    params.set('size', String(PAGE_SIZE))
    const { data } = await api.get<unknown>('/grammar-topics', {
      params,
      signal,
    })
    const parsed = topicPageSchema.parse(data)
    topics.push(...parsed.content)
    if (page + 1 >= parsed.totalPages) return topics
  }
}

/** Adds a topic typed by the learner. A taken title is 409 GRAMMAR_TOPIC_DUPLICATE. */
export async function createGrammarTopic(
  request: CreateGrammarTopicRequest,
): Promise<GrammarTopic> {
  const { data } = await api.post<unknown>('/grammar-topics', request)
  return topicSchema.parse(data)
}

/** The suggested topics of the active language, with the ones the learner has marked. */
export async function fetchCatalog(): Promise<CatalogEntry[]> {
  const { data } = await api.get<unknown>('/grammar-topics/catalog')
  return z.array(catalogEntrySchema).parse(data)
}

/** Adds a catalog entry as one of the learner's topics. */
export async function addFromCatalog(catalogId: string): Promise<GrammarTopic> {
  const { data } = await api.post<unknown>(
    `/grammar-topics/catalog/${encodeURIComponent(catalogId)}`,
  )
  return topicSchema.parse(data)
}

const statusChangeSchema = z.object({
  fromStatus: optional(z.enum(KNOWLEDGE_STATUSES)),
  toStatus: z.enum(KNOWLEDGE_STATUSES),
  reason: z.enum(['CREATED', 'MANUAL', 'MASTERY_RULE', 'DEMOTION_RULE']),
  changedAt: z.string(),
}) satisfies z.ZodType<ApiStatusChange>

export type StatusChange = z.infer<typeof statusChangeSchema>

export async function fetchGrammarTopic(id: string): Promise<GrammarTopic> {
  const { data } = await api.get<unknown>(
    `/grammar-topics/${encodeURIComponent(id)}`,
  )
  return topicSchema.parse(data)
}

/**
 * Partial update: absent fields stay unchanged and "" clears the description or notes.
 * MASTERED needs `confirmMastered: true`; NEW can't be chosen again.
 */
export async function updateGrammarTopic(
  id: string,
  request: UpdateGrammarTopicRequest,
): Promise<GrammarTopic> {
  const { data } = await api.patch<unknown>(
    `/grammar-topics/${encodeURIComponent(id)}`,
    request,
  )
  return topicSchema.parse(data)
}

/** Deletes the topic with its history and progress. */
export async function deleteGrammarTopic(id: string): Promise<void> {
  await api.delete(`/grammar-topics/${encodeURIComponent(id)}`)
}

/** Every status change of the topic, oldest first. */
export async function fetchStatusHistory(id: string): Promise<StatusChange[]> {
  const { data } = await api.get<unknown>(
    `/grammar-topics/${encodeURIComponent(id)}/status-history`,
  )
  return z.array(statusChangeSchema).parse(data)
}
