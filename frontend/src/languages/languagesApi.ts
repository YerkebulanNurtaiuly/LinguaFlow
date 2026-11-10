import { z } from 'zod'
import type {
  AddLanguageRequest,
  Language,
  UpdateLanguageRequest,
  UserLanguage,
} from '@/api/types'
import { api } from '@/lib/api'

export const CEFR_LEVELS = ['A1', 'A2', 'B1', 'B2', 'C1', 'C2'] as const

export const EXERCISE_TYPES = [
  'FILL_BLANK',
  'MULTIPLE_CHOICE',
  'TRANSLATION',
  'SENTENCE_CONSTRUCTION',
  'ERROR_CORRECTION',
  'WORD_ORDER',
  'TRANSFORMATION',
] as const

const languageSchema = z.object({
  code: z.string(),
  name: z.string(),
  nativeName: z.string(),
  supportedAsTarget: z.boolean(),
  supportedAsExplanation: z.boolean(),
}) satisfies z.ZodType<Language>

const userLanguageSchema = z.object({
  languageCode: z.string(),
  explanationLanguageCode: z.string(),
  selfAssessedLevel: z
    .enum(CEFR_LEVELS)
    .nullish()
    .transform((level) => level ?? undefined),
  active: z.boolean(),
  createdAt: z.string(),
  settings: z.object({
    dailyReviewGoal: z.number(),
    newCardsPerDay: z.number(),
    practiceSessionLength: z.number(),
    preferredExerciseTypes: z.array(z.enum(EXERCISE_TYPES)),
  }),
}) satisfies z.ZodType<UserLanguage>

export type AvailableLanguage = z.infer<typeof languageSchema>
export type LearnedLanguage = z.infer<typeof userLanguageSchema>

/** All languages, with what each can be used for. */
export async function fetchLanguages(): Promise<AvailableLanguage[]> {
  const { data } = await api.get<unknown>('/languages')
  return z.array(languageSchema).parse(data)
}

/** The languages the current user learns (empty until onboarding is done). */
export async function fetchMyLanguages(): Promise<LearnedLanguage[]> {
  const { data } = await api.get<unknown>('/users/me/languages')
  return z.array(userLanguageSchema).parse(data)
}

export async function addLanguage(
  request: AddLanguageRequest,
): Promise<LearnedLanguage> {
  const { data } = await api.post<unknown>('/users/me/languages', request)
  return userLanguageSchema.parse(data)
}

/** Stops learning a language. Permanently deletes ALL learning data for it. */
export async function removeLanguage(languageCode: string): Promise<void> {
  await api.delete(`/users/me/languages/${encodeURIComponent(languageCode)}`)
}

/** Partial update: absent fields (also inside `settings`) stay unchanged. */
export async function updateLanguage(
  languageCode: string,
  request: UpdateLanguageRequest,
): Promise<LearnedLanguage> {
  const { data } = await api.patch<unknown>(
    `/users/me/languages/${encodeURIComponent(languageCode)}`,
    request,
  )
  return userLanguageSchema.parse(data)
}
