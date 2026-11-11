import { z } from 'zod'
import type { ExerciseType } from '@/api/types'
import {
  CARD_DIRECTIONS,
  CEFR_LEVELS,
  EXERCISE_TYPES,
} from '@/languages/languagesApi'
import { newPasswordSchema } from '@/pages/auth/authForms'

export const profileSchema = z.object({
  displayName: z
    .string()
    .trim()
    .min(1, 'Enter your name')
    .max(100, 'Name is too long'),
  interfaceLanguage: z.string().min(1, 'Choose a language'),
  timezone: z.string().min(1, 'Choose a time zone'),
})
export type ProfileValues = z.infer<typeof profileSchema>

export const passwordSchema = z
  .object({
    currentPassword: z.string().min(1, 'Enter your current password'),
    newPassword: newPasswordSchema,
    confirmPassword: z.string(),
  })
  .refine((values) => values.confirmPassword === values.newPassword, {
    path: ['confirmPassword'],
    message: "Passwords don't match",
  })
  .refine((values) => values.newPassword !== values.currentPassword, {
    path: ['newPassword'],
    message: 'Choose a password different from your current one',
  })
export type PasswordValues = z.infer<typeof passwordSchema>

export const deleteAccountSchema = z.object({
  password: z.string().min(1, 'Enter your password to confirm'),
})
export type DeleteAccountValues = z.infer<typeof deleteAccountSchema>

/** A whole number in [min, max]; an emptied number input arrives as NaN. */
const wholeNumber = (min: number, max: number) =>
  z
    .number({ error: 'Enter a number' })
    .int('Enter a whole number')
    .min(min, `Use a number from ${min} to ${max}`)
    .max(max, `Use a number from ${min} to ${max}`)

/** Ranges match the backend (USR-03 SettingsUpdate) and the DB checks. */
export const learningSettingsSchema = z.object({
  explanationLanguageCode: z.string().min(1),
  /** '' while the learner never set a level (the backend can't unset one) */
  selfAssessedLevel: z.union([z.enum(CEFR_LEVELS), z.literal('')]),
  dailyReviewGoal: wholeNumber(1, 1000),
  newCardsPerDay: wholeNumber(0, 500),
  practiceSessionLength: wholeNumber(5, 200),
  preferredExerciseTypes: z.array(z.enum(EXERCISE_TYPES)),
  cardDirections: z
    .array(z.enum(CARD_DIRECTIONS))
    .min(1, 'Choose at least one direction to review.'),
})
export type LearningSettingsValues = z.infer<typeof learningSettingsSchema>

export const EXERCISE_TYPE_LABELS: Record<ExerciseType, string> = {
  FILL_BLANK: 'Fill in the blank',
  MULTIPLE_CHOICE: 'Multiple choice',
  TRANSLATION: 'Translation',
  SENTENCE_CONSTRUCTION: 'Build a sentence',
  ERROR_CORRECTION: 'Find the mistake',
  WORD_ORDER: 'Word order',
  TRANSFORMATION: 'Transform the sentence',
}

/** IANA zones the browser knows, plus UTC and the current value if it is missing. */
export function timeZoneOptions(current?: string): string[] {
  const zones = new Set(Intl.supportedValuesOf('timeZone'))
  zones.add('UTC')
  if (current) zones.add(current)
  return [...zones].sort()
}

export function browserTimeZone(): string | undefined {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || undefined
}
