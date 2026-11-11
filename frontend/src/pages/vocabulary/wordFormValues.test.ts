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
    expect(toFormField('verbForms.partizip2')).toBe('partizip2')
    expect(toFormField('verbForms')).toBe('praeteritum')
  })

  it('sends emptied choices in clear', () => {
    expect(
      toUpdateRequest(
        dog,
        parse({
          ...valuesOf(dog),
          partOfSpeech: '',
          gender: '',
          cefrLevel: '',
        }),
      ),
    ).toEqual({ clear: ['PART_OF_SPEECH', 'GENDER', 'CEFR_LEVEL'] })
  })

  it('leaves unchanged examples out and sends changed ones with their ids', () => {
    const withExample: VocabularyWord = {
      ...dog,
      examples: [
        {
          id: 'e1',
          sentence: 'Der Hund bellt.',
          translation: undefined,
          aiGenerated: true,
        },
      ],
    }
    expect(
      toUpdateRequest(withExample, parse(valuesOf(withExample))),
    ).toBeNull()

    const values = valuesOf(withExample)
    expect(
      toUpdateRequest(
        withExample,
        parse({
          ...values,
          examples: [{ ...values.examples[0]!, translation: 'The dog barks.' }],
        }),
      ),
    ).toEqual({
      examples: [
        {
          id: 'e1',
          sentence: 'Der Hund bellt.',
          translation: 'The dog barks.',
        },
      ],
    })
  })

  it('clears the verb forms when all three are emptied', () => {
    const verb: VocabularyWord = {
      ...dog,
      partOfSpeech: 'VERB',
      gender: undefined,
      plural: undefined,
      extra: { praeteritum: 'lief', partizip2: 'gelaufen', auxiliary: 'SEIN' },
    }
    expect(
      toUpdateRequest(
        verb,
        parse({
          ...valuesOf(verb),
          praeteritum: '',
          partizip2: '',
          auxiliary: '',
        }),
      ),
    ).toEqual({ clear: ['VERB_FORMS'] })
    expect(
      wordFormSchema.safeParse({ ...valuesOf(verb), partizip2: '' }).success,
    ).toBe(false)
  })
})
