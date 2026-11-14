import { z } from 'zod'
import type {
  CreateVocabularyRequest,
  UpdateVocabularyRequest,
} from '@/api/types'
import { CEFR_LEVELS } from '@/languages/languagesApi'
import {
  AUXILIARIES,
  GENDERS,
  PARTS_OF_SPEECH,
  verbFormsOf,
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

/** Most examples a word can have (the backend's limit). */
export const MAX_EXAMPLES = 10

const optionalEnum = <T extends readonly [string, ...string[]]>(values: T) =>
  z.union([z.enum(values), z.literal('')])

const text = (max: number, label: string) =>
  z
    .string()
    .max(max, `${label} can be at most ${max} characters.`)
    .transform((value) => value.trim())

/** Mirrors the backend limits of VocabularyDtos. */
export const wordFormSchema = z
  .object({
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
          // An existing example (edit mode); sent back so an unchanged AI example stays the AI's
          exampleId: z.string().optional(),
          aiGenerated: z.boolean().optional(),
          sentence: z
            .string()
            .trim()
            .min(1, 'Enter the example sentence or remove it.')
            .max(1000, 'The sentence can be at most 1000 characters.'),
          translation: text(1000, 'The translation'),
        }),
      )
      .max(MAX_EXAMPLES),
    // A verb's principal parts (VOC-06): all three or none
    praeteritum: text(100, 'The Präteritum'),
    partizip2: text(100, 'The Partizip II'),
    auxiliary: optionalEnum(AUXILIARIES),
  })
  .superRefine((values, context) => {
    const forms = [
      values.praeteritum.trim(),
      values.partizip2.trim(),
      values.auxiliary,
    ]
    if (forms.some(Boolean) && !forms.every(Boolean)) {
      const missing = !values.praeteritum.trim()
        ? 'praeteritum'
        : !values.partizip2.trim()
          ? 'partizip2'
          : 'auxiliary'
      context.addIssue({
        code: 'custom',
        path: [missing],
        message: 'Fill in all three verb forms, or none.',
      })
    }
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
  praeteritum: '',
  partizip2: '',
  auxiliary: '',
}

/** The form values of an existing word. */
export function valuesOf(word: VocabularyWord): WordFormInput {
  const verbForms = verbFormsOf(word)
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
    examples: word.examples.map((example) => ({
      exampleId: example.id,
      aiGenerated: example.aiGenerated,
      sentence: example.sentence,
      translation: example.translation ?? '',
    })),
    praeteritum: verbForms?.praeteritum ?? '',
    partizip2: verbForms?.partizip2 ?? '',
    auxiliary: verbForms?.auxiliary ?? '',
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

type ClearableField = NonNullable<UpdateVocabularyRequest['clear']>[number]

const sameExamples = (
  a: WordFormValues['examples'],
  b: WordFormInput['examples'],
) =>
  a.length === b.length &&
  a.every((example, i) => {
    const other = b[i]
    return (
      other !== undefined &&
      example.exampleId === other.exampleId &&
      example.sentence === other.sentence.trim() &&
      example.translation === other.translation.trim()
    )
  })

/**
 * Only what changed, as the PATCH expects: absent fields stay unchanged, an empty
 * string clears translation, plural or notes, and an emptied choice (type, gender,
 * level) or verb forms are sent in `clear` (VOC-06). Examples are sent as a whole list,
 * with the ids of existing ones. Null when nothing changed.
 */
export function toUpdateRequest(
  word: VocabularyWord,
  values: WordFormValues,
): UpdateVocabularyRequest | null {
  const original = valuesOf(word)
  const request: UpdateVocabularyRequest = {}
  const clear: ClearableField[] = []
  if (values.lemma !== original.lemma) request.lemma = values.lemma
  if (values.translation !== original.translation)
    request.translation = values.translation
  if (values.partOfSpeech !== original.partOfSpeech) {
    if (values.partOfSpeech) request.partOfSpeech = values.partOfSpeech
    else clear.push('PART_OF_SPEECH')
  }
  // Leaving nouns clears the gender on the server; don't send one that no longer applies
  if (hasNounFields(values.partOfSpeech) && values.gender !== original.gender) {
    if (values.gender) request.gender = values.gender
    else clear.push('GENDER')
  }
  if (hasNounFields(values.partOfSpeech) && values.plural !== original.plural)
    request.plural = values.plural
  if (values.cefrLevel !== original.cefrLevel) {
    if (values.cefrLevel) request.cefrLevel = values.cefrLevel
    else clear.push('CEFR_LEVEL')
  }
  if (values.status !== original.status && values.status !== 'MASTERED')
    request.status = values.status
  if (values.notes !== original.notes) request.notes = values.notes
  if (!sameSet(values.tagIds, original.tagIds)) request.tagIds = values.tagIds
  if (!sameExamples(values.examples, original.examples)) {
    request.examples = values.examples.map((example) => ({
      id: example.exampleId,
      sentence: example.sentence,
      translation: example.translation || undefined,
    }))
  }
  // Leaving verbs clears the verb forms on the server
  if (values.partOfSpeech === 'VERB') {
    const changed =
      values.praeteritum !== original.praeteritum ||
      values.partizip2 !== original.partizip2 ||
      values.auxiliary !== original.auxiliary
    if (changed && values.auxiliary) {
      request.verbForms = {
        praeteritum: values.praeteritum,
        partizip2: values.partizip2,
        auxiliary: values.auxiliary,
      }
    } else if (changed && original.auxiliary) {
      clear.push('VERB_FORMS')
    }
  }
  if (clear.length > 0) request.clear = clear
  return Object.keys(request).length > 0 ? request : null
}

/**
 * Backend field names → form field names; `examples[0].sentence` becomes
 * `examples.0.sentence`.
 */
export function toFormField(serverField: string): string {
  // The verb forms are flat in the form: verbForms.praeteritum is praeteritum
  if (serverField === 'verbForms') return 'praeteritum'
  if (serverField.startsWith('verbForms.'))
    return serverField.slice('verbForms.'.length)
  return serverField.replace(/\[(\d+)\]/g, '.$1')
}
