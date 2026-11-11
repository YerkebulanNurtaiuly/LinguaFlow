import { z } from 'zod'
import type {
  BulkVocabularyConfirmRequest,
  BulkVocabularyFormat,
  BulkVocabularyPreview,
  BulkVocabularyResult,
} from '@/api/types'
import { CEFR_LEVELS } from '@/languages/languagesApi'
import { api } from '@/lib/api'
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
  // The AI jobs enriching the new words, one per batch (VOC-05)
  aiJobIds: z.array(z.string()).default([]),
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
