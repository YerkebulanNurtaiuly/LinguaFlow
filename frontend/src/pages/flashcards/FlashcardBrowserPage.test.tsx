import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Flashcard } from '@/flashcards/flashcardsApi'
import { fetchHealth } from '@/lib/health'
import { fakeApi, sentBody } from '@/test/fakeApi'
import { renderApp } from '@/test/renderWithProviders'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

function card(overrides: Partial<Flashcard> = {}): Flashcard {
  return {
    id: 'c1',
    vocabularyItemId: 'w1',
    lemma: 'Hund',
    direction: 'TARGET_TO_NATIVE',
    suspended: false,
    content: {
      front: 'der Hund',
      hint: undefined,
      back: 'dog',
      plural: 'die Hunde',
      praeteritum: undefined,
      perfect: undefined,
      examples: [],
      complete: true,
    },
    srs: {
      state: 'REVIEW',
      dueAt: new Date(Date.now() + 3 * 86_400_000).toISOString(),
      intervalDays: 3,
      repetitions: 2,
      lapses: 1,
      lastReviewedAt: '2026-11-08T09:30:00Z',
    },
    createdAt: '2026-11-01T10:00:00Z',
    updatedAt: '2026-11-01T10:00:00Z',
    ...overrides,
  }
}

const reverse = card({
  id: 'c2',
  direction: 'NATIVE_TO_TARGET',
  suspended: true,
  content: { ...card().content, front: 'dog', hint: 'noun', back: 'der Hund' },
  srs: {
    state: 'NEW',
    dueAt: undefined,
    intervalDays: 0,
    repetitions: 0,
    lapses: 0,
    lastReviewedAt: undefined,
  },
})

const page = (content: Flashcard[]) => ({
  status: 200,
  data: {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length ? 1 : 0,
  },
})

describe('flashcard browser', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
  })

  it('lists cards with their direction, state and due date, filtered from the URL', async () => {
    fakeApi.on('GET', '/flashcards', page([card(), reverse]))
    await renderApp('/flashcards/browse?state=REVIEW&sort=due')

    const table = await screen.findByRole('table', {
      name: 'Your flashcards, 1 to 2 of 2',
    })
    const rows = within(table).getAllByRole('row')
    expect(
      within(rows[1]!).getByRole('button', {
        name: 'der Hund → dog, German → English',
      }),
    ).toBeInTheDocument()
    expect(within(rows[1]!).getByText('In 3 days')).toBeInTheDocument()
    expect(within(rows[2]!).getByText('Suspended')).toBeInTheDocument()
    expect(within(rows[2]!).getByText('Not started')).toBeInTheDocument()

    const query = new URLSearchParams(fakeApi.requests('/flashcards')[0]!.query)
    expect(query.get('state')).toBe('REVIEW')
    expect(query.get('sort')).toBe('dueAt,asc')
    expect(query.get('page')).toBe('0')
    expect(screen.getByRole('combobox', { name: 'State' })).toHaveValue(
      'REVIEW',
    )
  })

  it('searches and filters', async () => {
    const user = userEvent.setup()
    fakeApi.on('GET', '/flashcards', page([card()]))
    await renderApp('/flashcards/browse')
    await screen.findByRole('table')

    await user.type(screen.getByRole('searchbox', { name: 'Search' }), 'hund')
    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Suspended' }),
      'Suspended',
    )
    await waitFor(() => {
      const last = fakeApi.requests('/flashcards').at(-1)!
      const query = new URLSearchParams(last.query)
      expect(query.get('q')).toBe('hund')
      expect(query.get('suspended')).toBe('true')
    })

    // Clearing empties the search box and the filters (the unfiltered list is cached)
    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    await waitFor(() =>
      expect(screen.getByRole('searchbox', { name: 'Search' })).toHaveValue(''),
    )
    expect(screen.getByRole('combobox', { name: 'Suspended' })).toHaveValue('')
  })

  it('shows a card with its history and suspends it', async () => {
    const user = userEvent.setup()
    fakeApi.on('GET', '/flashcards', page([card()]))
    fakeApi.on('GET', '/flashcards/c1/reviews', {
      status: 200,
      data: {
        content: [
          {
            id: 'r2',
            flashcardId: 'c1',
            grade: 'GOOD',
            reviewedAt: '2026-11-08T09:30:00Z',
            durationMs: 4200,
            stateBefore: 'REVIEW',
            intervalBefore: 1,
            intervalAfter: 3,
            easeBefore: 2.5,
            easeAfter: 2.5,
          },
          {
            id: 'r1',
            flashcardId: 'c1',
            grade: 'AGAIN',
            reviewedAt: '2026-11-07T09:30:00Z',
            durationMs: undefined,
            stateBefore: 'REVIEW',
            intervalBefore: 4,
            intervalAfter: 1,
            easeBefore: 2.7,
            easeAfter: 2.5,
          },
        ],
        page: 0,
        size: 100,
        totalElements: 2,
        totalPages: 1,
      },
    })
    fakeApi.on('PATCH', '/flashcards/c1', {
      status: 200,
      data: card({ suspended: true }),
    })
    await renderApp('/flashcards/browse')

    await user.click(
      await screen.findByRole('button', { name: /der Hund → dog/ }),
    )
    const dialog = await screen.findByRole('dialog', { name: 'Hund' })
    expect(within(dialog).getByText('Lapses')).toBeInTheDocument()
    const history = await within(dialog).findByRole('table', {
      name: '2 reviews, newest first',
    })
    const rows = within(history).getAllByRole('row')
    expect(within(rows[1]!).getByText('Good')).toBeInTheDocument()
    expect(within(rows[1]!).getByText('1 → 3')).toBeInTheDocument()
    expect(within(rows[2]!).getByText('Again')).toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Suspend' }))
    expect(
      await within(dialog).findByRole('button', { name: 'Unsuspend' }),
    ).toBeInTheDocument()
    expect(sentBody('PATCH', '/flashcards/c1')).toEqual({ suspended: true })
  })

  it('explains an empty collection', async () => {
    fakeApi.on('GET', '/flashcards', page([]))
    await renderApp('/flashcards/browse')
    expect(await screen.findByText('No flashcards yet')).toBeInTheDocument()
  })

  it('is linked from the review page', async () => {
    fakeApi.on('GET', '/reviews/stats', {
      status: 200,
      data: {
        today: '2026-11-11',
        dueToday: 0,
        newAvailable: 0,
        reviewedToday: 0,
        dailyReviewGoal: 50,
        currentStreak: 0,
        longestStreak: 0,
        retention: { days: 30, reviews: 0, remembered: 0, rate: null },
        forecast: [],
      },
    })
    await renderApp('/flashcards')
    expect(
      await screen.findByRole('link', { name: 'All cards' }),
    ).toHaveAttribute('href', '/flashcards/browse')
  })
})
