import type { GradePreview } from './reviewsApi'

/**
 * When a grade would bring the card back, as a short label for its button: minutes for
 * learning steps ("10 min"), else the interval in days, months or years ("4 days",
 * "2 mo", "1.5 yr").
 */
export function formatInterval(preview: GradePreview, now: number): string {
  if (preview.state === 'LEARNING' || preview.state === 'RELEARNING') {
    const minutes = Math.max(
      1,
      Math.round((Date.parse(preview.dueAt) - now) / 60_000),
    )
    if (minutes < 60) return `${minutes} min`
    const hours = Math.round(minutes / 60)
    return `${hours} h`
  }
  const days = preview.intervalDays
  if (days < 31) return days === 1 ? '1 day' : `${days} days`
  if (days < 365) return `${Math.round(days / 30)} mo`
  const years = Math.round((days / 365) * 10) / 10
  return `${years} yr`
}

export type Article = 'der' | 'die' | 'das'

/** Splits a leading German article off ("der Hund" → der + "Hund"). */
export function splitArticle(text: string): {
  article?: Article
  rest: string
} {
  const match = /^(der|die|das)\s+(.+)$/i.exec(text.trim())
  if (!match) return { rest: text }
  return { article: match[1]!.toLowerCase() as Article, rest: match[2]! }
}

/** Lower case, NFC, ß as ss, spacing and trailing punctuation ignored. */
export function normalizeAnswer(text: string): string {
  return text
    .normalize('NFC')
    .toLowerCase()
    .replace(/ß/g, 'ss')
    .trim()
    .replace(/[.!?;:,]+$/u, '')
    .replace(/\s+/g, ' ')
    .trim()
}

export type AnswerCheck = 'correct' | 'article' | 'wrong'

/**
 * Compares a typed answer with the card's back. A back with several meanings ("house,
 * home") accepts any one of them. When the expected answer has an article, the article
 * counts: the right word with a missing or wrong article is `article`.
 */
export function checkAnswer(typed: string, expected: string): AnswerCheck {
  const answer = normalizeAnswer(typed)
  if (!answer) return 'wrong'
  const options = expected.split(/[,;/]/).map(normalizeAnswer).filter(Boolean)
  if (options.includes(answer) || normalizeAnswer(expected) === answer) {
    return 'correct'
  }
  const typedWord = normalizeAnswer(splitArticle(answer).rest)
  const nearly = options.some((option) => {
    const { article, rest } = splitArticle(option)
    return article !== undefined && normalizeAnswer(rest) === typedWord
  })
  return nearly ? 'article' : 'wrong'
}
