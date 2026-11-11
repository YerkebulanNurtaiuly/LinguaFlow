import { describe, expect, it } from 'vitest'
import { checkAnswer, formatInterval, splitArticle } from './reviewFormat'

const NOW = Date.parse('2026-11-11T10:00:00Z')

describe('formatInterval', () => {
  it.each([
    ['LEARNING', 0, '2026-11-11T10:01:00Z', '1 min'],
    ['RELEARNING', 0, '2026-11-11T10:10:00Z', '10 min'],
    ['LEARNING', 0, '2026-11-11T12:00:00Z', '2 h'],
    ['REVIEW', 1, '2026-11-11T19:00:00Z', '1 day'],
    ['REVIEW', 4, '2026-11-14T19:00:00Z', '4 days'],
    ['REVIEW', 60, '2027-01-10T19:00:00Z', '2 mo'],
    ['REVIEW', 548, '2028-05-11T19:00:00Z', '1.5 yr'],
  ] as const)('%s %i days due %s → %s', (state, intervalDays, dueAt, label) => {
    expect(formatInterval({ state, intervalDays, dueAt }, NOW)).toBe(label)
  })
})

describe('splitArticle', () => {
  it('splits a leading article off', () => {
    expect(splitArticle('der Hund')).toEqual({ article: 'der', rest: 'Hund' })
    expect(splitArticle('Die Katze')).toEqual({
      article: 'die',
      rest: 'Katze',
    })
    expect(splitArticle('laufen')).toEqual({ rest: 'laufen' })
    expect(splitArticle('derselbe')).toEqual({ rest: 'derselbe' })
  })
})

describe('checkAnswer', () => {
  it.each([
    ['dog', 'dog', 'correct'],
    ['  Dog. ', 'dog', 'correct'],
    ['home', 'house, home', 'correct'],
    ['house, home', 'house, home', 'correct'],
    ['der Hund', 'der Hund', 'correct'],
    ['die Strasse', 'die Straße', 'correct'],
    ['Hund', 'der Hund', 'article'],
    ['das Hund', 'der Hund', 'article'],
    ['Katze', 'der Hund', 'wrong'],
    ['', 'dog', 'wrong'],
  ] as const)('%j for %j is %s', (typed, expected, result) => {
    expect(checkAnswer(typed, expected)).toBe(result)
  })
})
