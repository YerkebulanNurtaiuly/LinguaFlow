import { describe, expect, it } from 'vitest'
import type { VocabularyWord } from '@/vocabulary/vocabularyApi'
import {
  EMPTY_WORD,
  toCreateRequest,
  toFormField,
  toUpdateRequest,
  valuesOf,
  wordFormSchema,
  type WordFormInput,
} from './wordFormValues'

const dog: VocabularyWord = {
  id: 'w1',
  lemma: 'der Hund',
  article: 'der',
  partOfSpeech: 'NOUN',
  translation: 'dog',
  gender: 'MASCULINE',
  plural: 'die Hunde',
  cefrLevel: 'A1',
  status: 'LEARNING',
  aiStatus: 'READY',
  aiGeneratedFields: [],
  notes: undefined,
  examples: [],
  tags: [{ id: 't1', name: 'Tiere' }],
  createdAt: '2026-11-01T10:00:00Z',
  updatedAt: '2026-11-01T10:00:00Z',
  version: 0,
}

const parse = (input: WordFormInput) => wordFormSchema.parse(input)

describe('word form values', () => {
  it('sends only the word when nothing else is filled in', () => {
    expect(toCreateRequest(parse({ ...EMPTY_WORD, lemma: '  Hund ' }))).toEqual(
      { lemma: 'Hund', status: 'LEARNING' },
    )
  })

  it('drops noun fields for other word types', () => {
    const request = toCreateRequest(
      parse({
        ...EMPTY_WORD,
        lemma: 'laufen',
        partOfSpeech: 'VERB',
        gender: 'NEUTER',
        plural: 'x',
      }),
    )
    expect(request).toEqual({
      lemma: 'laufen',
      partOfSpeech: 'VERB',
      status: 'LEARNING',
    })
  })

  it('sends nothing for an unchanged word', () => {
    expect(toUpdateRequest(dog, parse(valuesOf(dog)))).toBeNull()
  })

  it('sends only changed fields, with empty strings to clear text', () => {
    const values = parse({
      ...valuesOf(dog),
      translation: ' ',
      notes: 'bellt',
      tagIds: ['t2', 't1'],
    })
    expect(toUpdateRequest(dog, values)).toEqual({
      translation: '',
      notes: 'bellt',
      tagIds: ['t2', 't1'],
    })
  })

  it('treats reordered tags as unchanged', () => {
    const word = { ...dog, tags: [...dog.tags, { id: 't2', name: 'B' }] }
    expect(
      toUpdateRequest(word, parse({ ...valuesOf(word), tagIds: ['t2', 't1'] })),
    ).toBeNull()
  })

  it('leaves gender to the server when the word stops being a noun', () => {
    expect(
      toUpdateRequest(
        dog,
        parse({ ...valuesOf(dog), partOfSpeech: 'VERB', plural: '' }),
      ),
    ).toEqual({ partOfSpeech: 'VERB' })
  })

  it('maps indexed server fields onto form fields', () => {
    expect(toFormField('examples[2].sentence')).toBe('examples.2.sentence')
    expect(toFormField('lemma')).toBe('lemma')
  })
})
