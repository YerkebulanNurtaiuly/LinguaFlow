import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchHealth } from '@/lib/health'
import type { QueueItem, ReviewQueue, ReviewStats } from '@/reviews/reviewsApi'
import { fakeApi, sentBody } from '@/test/fakeApi'
import { findAlert } from '@/test/queries'
import { renderApp } from '@/test/renderWithProviders'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

const preview = (
  state: 'LEARNING' | 'REVIEW',
  intervalDays: number,
  minutes = 0,
) => ({
  state,
  intervalDays,
  dueAt: new Date(Date.now() + minutes * 60_000).toISOString(),
})

function item(
  id: string,
  direction: 'TARGET_TO_NATIVE' | 'NATIVE_TO_TARGET',
  front: string,
  back: string,
  extra: Partial<QueueItem['card']['content']> = {},
): QueueItem {
  return {
    card: {
      id,
      vocabularyItemId: `w-${id}`,
      lemma: 'Hund',
      direction,
      suspended: false,
      content: {
        front,
        hint: undefined,
        back,
        plural: undefined,
        praeteritum: undefined,
        perfect: undefined,
        examples: [],
        complete: true,
        ...extra,
      },
      srs: {
        state: 'NEW',
        dueAt: undefined,
        intervalDays: 0,
        repetitions: 0,
        lapses: 0,
        lastReviewedAt: undefined,
      },
      createdAt: '2026-11-01T10:00:00Z',
      updatedAt: '2026-11-01T10:00:00Z',
    },
    previews: {
      again: preview('LEARNING', 0, 1),
      hard: preview('LEARNING', 0, 6),
      good: preview('LEARNING', 0, 10),
      easy: preview('REVIEW', 4),
    },
  }
}

const hund = item('c1', 'TARGET_TO_NATIVE', 'der Hund', 'dog', {
  plural: 'die Hunde',
  examples: [{ sentence: 'Der Hund bellt.', translation: 'The dog barks.' }],
})
const laufen = item('c2', 'NATIVE_TO_TARGET', 'to run', 'laufen', {
  hint: 'verb',
  praeteritum: 'lief',
  perfect: 'ist gelaufen',
})
const katze = item('c3', 'NATIVE_TO_TARGET', 'cat', 'die Katze')

function queue(cards: QueueItem[], next?: string): ReviewQueue {
  return {
    cards,
    dueCount: cards.length,
    newCount: 0,
    today: '2026-11-11',
    reviewedToday: 0,
    dailyReviewGoal: 50,
    newCardsToday: 0,
    newCardsPerDay: 20,
    nextLearningDueAt: next,
  }
}

/** Ending a session submits, then asks for more cards; slow CI runners need longer. */
const SESSION_END_TIMEOUT = 3000

const stats: ReviewStats = {
  today: '2026-11-11',
  dueToday: 12,
  newAvailable: 5,
  reviewedToday: 8,
  dailyReviewGoal: 50,
  currentStreak: 3,
  longestStreak: 9,
  retention: { days: 30, reviews: 40, remembered: 35, rate: 0.875 },
  forecast: [
    { date: '2026-11-11', due: 12 },
    { date: '2026-11-12', due: 4 },
    { date: '2026-11-13', due: 0 },
    { date: '2026-11-14', due: 7 },
    { date: '2026-11-15', due: 1 },
    { date: '2026-11-16', due: 0 },
    { date: '2026-11-17', due: 2 },
  ],
}

/** Answers GET /reviews/due with each queue in turn, then empty. */
function queues(...batches: ReviewQueue[]) {
  let call = 0
  fakeApi.on('GET', '/reviews/due', () => ({
    status: 200,
    data: batches[call++] ?? queue([]),
  }))
}

function acceptReviews() {
  fakeApi.on('POST', '/reviews', (config) => {
    const body = JSON.parse(config.data as string) as {
      flashcardId: string
      grade: string
    }
    return {
      status: 201,
      data: {
        review: {
          id: crypto.randomUUID(),
          flashcardId: body.flashcardId,
          grade: body.grade,
          reviewedAt: '2026-11-11T10:00:00Z',
          durationMs: 1000,
          stateBefore: 'NEW',
          intervalBefore: 0,
          intervalAfter: 0,
          easeBefore: 2.5,
          easeAfter: 2.5,
        },
        card: [hund, laufen, katze].find((i) => i.card.id === body.flashcardId)!
          .card,
        replayed: false,
      },
    }
  })
}

async function openReviews(data: ReviewStats = stats) {
  fakeApi.on('GET', '/reviews/stats', { status: 200, data })
  await renderApp('/flashcards')
  await screen.findByRole('heading', { level: 1, name: 'Flashcards' })
}

describe('flashcard review page', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
    try {
      localStorage.removeItem('lf-review-typing')
    } catch {
      // ignore
    }
  })

  it('shows what is due, streaks, retention and the week ahead', async () => {
    await openReviews()

    expect(await screen.findByText('17 cards')).toBeInTheDocument()
    expect(screen.getByText('12 due today · 5 new')).toBeInTheDocument()
    expect(screen.getByText('8 / 50')).toBeInTheDocument()
    expect(screen.getByText('3 days')).toBeInTheDocument()
    expect(screen.getByText('Longest: 9 days')).toBeInTheDocument()
    expect(screen.getByText('88%')).toBeInTheDocument()
    const forecast = screen.getByRole('list', { name: 'Cards due by day' })
    expect(within(forecast).getAllByRole('listitem')).toHaveLength(7)
    expect(within(forecast).getByText('Today')).toBeInTheDocument()
    expect(within(forecast).getByText('Thu')).toBeInTheDocument()
  })

  it('says when everything is done', async () => {
    await openReviews({
      ...stats,
      dueToday: 0,
      newAvailable: 0,
      retention: { days: 30, reviews: 0, remembered: 0, rate: undefined },
    })

    expect(await screen.findByText('All caught up')).toBeInTheDocument()
    expect(
      screen.getByText('Nothing is due right now. Next: 4 cards on Thu.'),
    ).toBeInTheDocument()
    expect(screen.getByText('—')).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Start review' }),
    ).not.toBeInTheDocument()
  })

  it('reviews cards with the keyboard and the grade buttons, then sums up', async () => {
    const user = userEvent.setup()
    queues(queue([hund, laufen]), queue([], '2026-11-11T10:12:00Z'))
    acceptReviews()
    await openReviews()

    await user.click(
      await screen.findByRole('button', { name: 'Start review' }),
    )
    const question = await screen.findByRole('region', { name: 'Question' })
    expect(within(question).getByText('German → English')).toBeInTheDocument()
    // The article in its gender's colour
    expect(within(question).getByText('der')).toHaveClass('text-article-der')
    expect(screen.getByText('0 of 2 reviewed')).toBeInTheDocument()
    expect(
      screen.queryByRole('group', { name: 'How well did you know it?' }),
    ).not.toBeInTheDocument()

    // Space shows the answer, with plural and examples
    await user.keyboard(' ')
    const answer = await screen.findByRole('region', { name: 'Answer' })
    expect(within(answer).getByText('dog')).toBeInTheDocument()
    expect(within(answer).getByText('die Hunde')).toBeInTheDocument()
    expect(within(answer).getByText('Der Hund bellt.')).toBeInTheDocument()
    const grades = screen.getByRole('group', {
      name: 'How well did you know it?',
    })
    expect(
      within(grades).getByRole('button', { name: 'Again, again in 1 min' }),
    ).toBeInTheDocument()
    expect(
      within(grades).getByRole('button', { name: 'Easy, again in 4 days' }),
    ).toBeInTheDocument()

    // 3 grades it GOOD
    await user.keyboard('3')
    await screen.findByText('1 of 2 reviewed')
    const first = sentBody('POST', '/reviews') as Record<string, unknown>
    expect(first).toMatchObject({ flashcardId: 'c1', grade: 'GOOD' })
    expect(first.reviewToken).toMatch(/^[0-9a-f-]{36}$/)
    expect(typeof first.durationMs).toBe('number')

    // The second card, English → German with its hint and verb forms
    const next = screen.getByRole('region', { name: 'Question' })
    expect(within(next).getByText('(verb)')).toBeInTheDocument()
    await user.click(within(next).getByRole('button', { name: /Show answer/ }))
    expect(
      within(await screen.findByRole('region', { name: 'Answer' })).getByText(
        'ist gelaufen',
      ),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /^Again/ }))

    // Nothing more due now: the summary
    expect(
      await screen.findByRole(
        'heading',
        { name: 'Session done' },
        { timeout: SESSION_END_TIMEOUT },
      ),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/2 cards in .* · 50% remembered/),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/Cards you're still learning come back at/),
    ).toBeInTheDocument()
    expect(fakeApi.requests('/reviews')).toHaveLength(2)

    await user.click(screen.getByRole('button', { name: 'Back to overview' }))
    expect(
      await screen.findByRole('button', { name: 'Start review' }),
    ).toBeInTheDocument()
  })

  it('checks a typed answer, article included', async () => {
    const user = userEvent.setup()
    queues(queue([katze]))
    acceptReviews()
    await openReviews()

    await user.click(
      await screen.findByRole('checkbox', { name: 'Type the answer' }),
    )
    expect(localStorage.getItem('lf-review-typing')).toBe('true')
    await user.click(screen.getByRole('button', { name: 'Start review' }))

    const input = await screen.findByRole('textbox', { name: 'Your answer' })
    expect(input).toHaveFocus()
    // Keys typed into the answer don't flip or grade the card
    await user.type(input, 'Katze 3{Backspace}{Backspace}{Enter}')

    const answer = await screen.findByRole('region', { name: 'Answer' })
    expect(
      within(answer).getByText('Almost: check the article'),
    ).toBeInTheDocument()
    expect(within(answer).getByText('die')).toHaveClass('text-article-die')
    // HARD is suggested
    expect(screen.getByRole('button', { name: /^Hard/ })).toHaveClass('ring-2')
    expect(fakeApi.requests('/reviews')).toHaveLength(0)

    await user.keyboard('2')
    await screen.findByRole(
      'heading',
      { name: 'Session done' },
      { timeout: SESSION_END_TIMEOUT },
    )
    expect(sentBody('POST', '/reviews')).toMatchObject({ grade: 'HARD' })
  })

  it('keeps the card and its token when grading fails', async () => {
    const user = userEvent.setup()
    queues(queue([hund]))
    let attempts = 0
    fakeApi.on('POST', '/reviews', () =>
      attempts++ === 0 ? { status: 0 } : { status: 500 },
    )
    await openReviews()

    await user.click(
      await screen.findByRole('button', { name: 'Start review' }),
    )
    await user.keyboard(' ')
    await user.keyboard('3')
    expect(await findAlert()).toHaveTextContent(/connect/i)
    await user.click(screen.getByRole('button', { name: /^Good/ }))
    await waitFor(() => expect(fakeApi.requests('/reviews')).toHaveLength(2))

    const [first, second] = fakeApi.requests('/reviews')
    expect((first!.body as { reviewToken: string }).reviewToken).toBe(
      (second!.body as { reviewToken: string }).reviewToken,
    )
    expect(screen.getByRole('region', { name: 'Answer' })).toBeInTheDocument()
  })
})
