import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'
import type {
  BulkVocabularyConfirmRequest,
  BulkVocabularyFormat,
  BulkVocabularyPreview,
  BulkVocabularyResult,
} from '@/api/types'
import { CEFR_LEVELS } from '@/languages/languagesApi'
import { api } from '@/lib/api'
import { VOCABULARY_QUERY_KEY } from './queries'
import { PARTS_OF_SPEECH } from './vocabularyApi'

/** Most lines one preview or confirm may carry (the backend's limit). */
export const MAX_BULK_LINES = 500

const optional = <T extends z.ZodType>(schema: T) =>
  schema.nullish().transform((value) => value ?? undefined)

const previewLineSchema = z.object({
  line: z.number(),
  text: z.string(),
  status: z.enum(['NEW', 'DUPLICATE', 'INVALID']),
  lemma: optional(z.string()),
  translation: optional(z.string()),
  partOfSpeech: optional(z.enum(PARTS_OF_SPEECH)),
  cefrLevel: optional(z.enum(CEFR_LEVELS)),
  notes: optional(z.string()),
  existingId: optional(z.string()),
  duplicateOfLine: optional(z.number()),
  message: optional(z.string()),
})

const previewSchema = z.object({
  lines: z.array(previewLineSchema),
  newCount: z.number(),
  duplicateCount: z.number(),
  invalidCount: z.number(),
}) satisfies z.ZodType<BulkVocabularyPreview>

const resultSchema = z.object({
  results: z.array(
    z.object({
      index: z.number(),
      lemma: optional(z.string()),
      status: z.enum(['CREATED', 'DUPLICATE', 'INVALID']),
      id: optional(z.string()),
      existingId: optional(z.string()),
      field: optional(z.string()),
      message: optional(z.string()),
    }),
  ),
  createdCount: z.number(),
  duplicateCount: z.number(),
  invalidCount: z.number(),
}) satisfies z.ZodType<BulkVocabularyResult>

export type BulkPreview = z.infer<typeof previewSchema>
export type BulkPreviewLine = z.infer<typeof previewLineSchema>
export type BulkResult = z.infer<typeof resultSchema>

/** Parses pasted text or CSV and says per line whether it's new; nothing is saved. */
export async function previewBulk(
  text: string,
  format: BulkVocabularyFormat,
): Promise<BulkPreview> {
  const { data } = await api.post<unknown>('/vocabulary/bulk', {
    text,
    format,
  })
  return previewSchema.parse(data)
}

/** Adds the chosen lines, each on its own, and reports what happened to each. */
export async function confirmBulk(
  request: BulkVocabularyConfirmRequest,
): Promise<BulkResult> {
  const { data } = await api.post<unknown>('/vocabulary/bulk/confirm', request)
  return resultSchema.parse(data)
}

const countSchema = z.object({ totalElements: z.number() })

/** How many of the learner's words AI hasn't processed yet. */
export async function fetchPendingAiCount(): Promise<number> {
  const params = new URLSearchParams({ aiStatus: 'PENDING_AI', size: '1' })
  const { data } = await api.get<unknown>('/vocabulary', { params })
  return countSchema.parse(data).totalElements
}

/** Polls the pending count while `enabled`, stopping once nothing is pending. */
export function usePendingAiCount(enabled: boolean) {
  return useQuery({
    queryKey: [...VOCABULARY_QUERY_KEY, 'pending-ai'],
    queryFn: fetchPendingAiCount,
    enabled,
    refetchInterval: (query) =>
      query.state.data === 0 ? false : PENDING_POLL_MS,
  })
}

export const PENDING_POLL_MS = 10_000
