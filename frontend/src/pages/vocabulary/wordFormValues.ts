import { z } from 'zod'
import type {
  CreateVocabularyRequest,
  UpdateVocabularyRequest,
} from '@/api/types'
import { CEFR_LEVELS } from '@/languages/languagesApi'
import {
  GENDERS,
  PARTS_OF_SPEECH,
  type Gender,
  type KnowledgeStatus,
  type VocabularyWord,
} from '@/vocabulary/vocabularyApi'

/** Statuses a learner may choose; MASTERED is earned through practice. */
export const SELECTABLE_STATUSES = ['NEW', 'LEARNING', 'LEARNED'] as const

export const STATUS_LABELS: Record<KnowledgeStatus, string> = {
  NEW: 'New',
  LEARNING: 'Learning',
  LEARNED: 'Learned',
  MASTERED: 'Mastered',
}

export const GENDER_LABELS: Record<Gender, string> = {
  MASCULINE: 'Masculine (der)',
  FEMININE: 'Feminine (die)',
  NEUTER: 'Neuter (das)',
}

/** Most examples a new word can have (the backend's limit). */
export const MAX_EXAMPLES = 10

const optionalEnum = <T extends readonly [string, ...string[]]>(values: T) =>
  z.union([z.enum(values), z.literal('')])

const text = (max: number, label: string) =>
  z
    .string()
    .max(max, `${label} can be at most ${max} characters.`)
    .transform((value) => value.trim())

/** Mirrors the backend limits of VocabularyDtos. */
export const wordFormSchema = z.object({
  lemma: z
    .string()
    .trim()
    .min(1, 'Enter the word or phrase.')
    .max(200, 'The word can be at most 200 characters.'),
  translation: text(500, 'The translation'),
  partOfSpeech: optionalEnum(PARTS_OF_SPEECH),
  gender: optionalEnum(GENDERS),
  plural: text(200, 'The plural'),
  cefrLevel: optionalEnum(CEFR_LEVELS),
  status: z.enum(['NEW', 'LEARNING', 'LEARNED', 'MASTERED']),
  notes: text(2000, 'Notes'),
  tagIds: z.array(z.string()).max(20, 'A word can have at most 20 tags.'),
  examples: z
    .array(
      z.object({
        sentence: z
          .string()
          .trim()
          .min(1, 'Enter the example sentence or remove it.')
          .max(1000, 'The sentence can be at most 1000 characters.'),
        translation: text(1000, 'The translation'),
      }),
    )
    .max(MAX_EXAMPLES),
})

export type WordFormInput = z.input<typeof wordFormSchema>
export type WordFormValues = z.output<typeof wordFormSchema>

export const EMPTY_WORD: WordFormInput = {
  lemma: '',
  translation: '',
  partOfSpeech: '',
  gender: '',
  plural: '',
  cefrLevel: '',
  status: 'LEARNING',
  notes: '',
  tagIds: [],
  examples: [],
}

/** The form values of an existing word (examples aren't edited in the form). */
export function valuesOf(word: VocabularyWord): WordFormInput {
  return {
    lemma: word.lemma,
    translation: word.translation ?? '',
    partOfSpeech: word.partOfSpeech ?? '',
    gender: word.gender ?? '',
    plural: word.plural ?? '',
    cefrLevel: word.cefrLevel ?? '',
    status: word.status,
    notes: word.notes ?? '',
    tagIds: word.tags.map((tag) => tag.id),
    examples: [],
  }
}

/** Gender and plural only apply to nouns, or to words whose type isn't known yet. */
export const hasNounFields = (partOfSpeech: string) =>
  partOfSpeech === '' || partOfSpeech === 'NOUN'

const orUndefined = <T extends string>(value: T | ''): T | undefined =>
  value === '' ? undefined : value

export function toCreateRequest(
  values: WordFormValues,
): CreateVocabularyRequest {
  const nounFields = hasNounFields(values.partOfSpeech)
  return {
    lemma: values.lemma,
    translation: values.translation || undefined,
    partOfSpeech: orUndefined(values.partOfSpeech),
    gender: nounFields ? orUndefined(values.gender) : undefined,
    plural: nounFields ? values.plural || undefined : undefined,
    cefrLevel: orUndefined(values.cefrLevel),
    status: values.status === 'MASTERED' ? undefined : values.status,
    notes: values.notes || undefined,
    tagIds: values.tagIds.length > 0 ? values.tagIds : undefined,
    examples:
      values.examples.length > 0
        ? values.examples.map((example) => ({
            sentence: example.sentence,
            translation: example.translation || undefined,
          }))
        : undefined,
  }
}

const sameSet = (a: string[], b: string[]) =>
  a.length === b.length && a.every((value) => b.includes(value))

/**
 * Only what changed, as the PATCH expects: absent fields stay unchanged and an empty
 * string clears translation, plural or notes. Choices (type, gender, level) can't be
 * cleared through the API, so the form doesn't offer an empty option once they're set.
 * Null when nothing changed.
 */
export function toUpdateRequest(
  word: VocabularyWord,
  values: WordFormValues,
): UpdateVocabularyRequest | null {
  const original = valuesOf(word)
  const request: UpdateVocabularyRequest = {}
  if (values.lemma !== original.lemma) request.lemma = values.lemma
  if (values.translation !== original.translation)
    request.translation = values.translation
  if (values.partOfSpeech !== original.partOfSpeech && values.partOfSpeech)
    request.partOfSpeech = values.partOfSpeech
  // Leaving nouns clears the gender on the server; don't send one that no longer applies
  if (
    hasNounFields(values.partOfSpeech) &&
    values.gender !== original.gender &&
    values.gender
  )
    request.gender = values.gender
  if (hasNounFields(values.partOfSpeech) && values.plural !== original.plural)
    request.plural = values.plural
  if (values.cefrLevel !== original.cefrLevel && values.cefrLevel)
    request.cefrLevel = values.cefrLevel
  if (values.status !== original.status && values.status !== 'MASTERED')
    request.status = values.status
  if (values.notes !== original.notes) request.notes = values.notes
  if (!sameSet(values.tagIds, original.tagIds)) request.tagIds = values.tagIds
  return Object.keys(request).length > 0 ? request : null
}

/**
 * Backend field names → form field names; `examples[0].sentence` becomes
 * `examples.0.sentence`.
 */
export function toFormField(serverField: string): string {
  return serverField.replace(/\[(\d+)\]/g, '.$1')
}
