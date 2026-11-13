import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchHealth } from '@/lib/health'
import { fakeApi, problem, sentBody } from '@/test/fakeApi'
import { findAlert } from '@/test/queries'
import {
  emptyProgressStats,
  emptyReviewStats,
  renderApp,
} from '@/test/renderWithProviders'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

const overview = {
  currentLesson: {
    id: 'l1',
    title: 'Lektion 5',
    studiedOn: '2026-11-10',
    words: 8,
    topics: 2,
  },
  words: 53,
  freshWords: 12,
  topics: 10,
  practiceSessionLength: 15,
  inProgress: null,
}

function practiceSession(id: string) {
  return {
    id,
    languageCode: 'de',
    mode: 'MIXED',
    status: 'IN_PROGRESS',
    studySessionId: null,
    config: {
      length: 15,
      includeFlashcards: true,
      includeExercises: true,
      newRatio: 0.6,
      seed: 7,
    },
    startedAt: '2026-11-13T09:00:00Z',
    completedAt: null,
    items: [],
    exerciseSet: { id: 's1', status: 'GENERATING', requested: 5 },
    summary: null,
  }
}

describe('dashboard', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
  })

  it("shows today's figures, recent sessions and weak topics", async () => {
    fakeApi.on('GET', '/reviews/stats', {
      status: 200,
      data: {
        ...emptyReviewStats,
        dueToday: 12,
        newAvailable: 5,
        reviewedToday: 8,
      },
    })
    fakeApi.on('GET', '/progress/stats', {
      status: 200,
      data: { ...emptyProgressStats, currentStreak: 4, longestStreak: 9 },
    })
    fakeApi.on('GET', '/practice-sessions', {
      status: 200,
      data: {
        content: [
          {
            id: 'p1',
            mode: 'FULL_REVISION',
            status: 'COMPLETED',
            studySessionId: null,
            startedAt: '2026-11-12T09:00:00Z',
            completedAt: '2026-11-12T09:20:00Z',
            items: 20,
            answered: 18,
          },
        ],
        page: 0,
        size: 5,
        totalElements: 1,
        totalPages: 1,
      },
    })
    fakeApi.on('GET', '/progress/weak-points', {
      status: 200,
      data: {
        grammarTopics: [
          {
            id: 't1',
            name: 'Dativ',
            translation: null,
            status: 'LEARNING',
            score: 0.56,
            answers: 10,
            recentAccuracy: 0.4,
            lastPracticedAt: '2026-11-12T09:00:00Z',
            reasons: [
              { code: 'LOW_ACCURACY', value: 0.4, errorType: null },
              {
                code: 'FREQUENT_MISTAKE',
                value: 6,
                errorType: 'WRONG_CASE',
              },
            ],
          },
        ],
        words: [],
      },
    })
    await renderApp('/dashboard')

    const today = await screen.findByRole('region', { name: 'Today' })
    expect(today).toHaveTextContent('17 cards')
    expect(today).toHaveTextContent('12 to review, 5 new')
    expect(
      within(today).getByRole('link', { name: 'Start review' }),
    ).toHaveAttribute('href', '/flashcards')
    const ring = within(today).getByRole('meter', { name: 'Daily review goal' })
    expect(ring).toHaveAttribute('aria-valuetext', '8 of 50 reviews')
    expect(today).toHaveTextContent('42 to go')
    expect(today).toHaveTextContent('4 days')
    expect(today).toHaveTextContent('Longest: 9 days')

    const recent = screen.getByRole('region', { name: 'Recent practice' })
    const link = await within(recent).findByRole('link', {
      name: /Full revision/,
    })
    expect(link).toHaveAttribute('href', '/practice/sessions/p1')
    expect(link).toHaveTextContent('18 of 20 items')

    const weak = screen.getByRole('region', { name: 'Weak topics' })
    expect(
      await within(weak).findByRole('link', { name: 'Dativ' }),
    ).toHaveAttribute('href', '/grammar/t1')
    expect(weak).toHaveTextContent('56% weak')
    expect(weak).toHaveTextContent('40% right lately · Wrong case 6×')
  })

  it('starts a practice mode in one click', async () => {
    const user = userEvent.setup()
    fakeApi.on('GET', '/practice-sessions/overview', {
      status: 200,
      data: overview,
    })
    fakeApi.on('POST', '/practice-sessions', {
      status: 201,
      data: { practiceSession: practiceSession('p9'), aiJobId: 'j1' },
    })
    fakeApi.on('GET', '/practice-sessions/p9', {
      status: 200,
      data: practiceSession('p9'),
    })
    await renderApp('/dashboard')

    const quick = await screen.findByRole('region', { name: 'Practise now' })
    await user.click(
      within(quick).getByRole('button', { name: /Mixed practice/ }),
    )

    expect(
      await screen.findByRole('heading', { name: 'Writing your exercises…' }),
    ).toBeInTheDocument()
    expect(sentBody('POST', '/practice-sessions')).toEqual({
      mode: 'MIXED',
      length: 15,
      includeFlashcards: true,
      includeExercises: true,
    })
  })

  it('starts weak-spot exercises, and offers no lesson practice without a lesson', async () => {
    const user = userEvent.setup()
    fakeApi.on('POST', '/progress/weak-spots/exercises', {
      status: 202,
      data: {
        exerciseSetId: 'set1',
        aiJobId: 'j2',
        grammarTopics: [],
        words: 0,
        focusMistakes: ['WRONG_CASE in Dativ (6 times)'],
      },
    })
    fakeApi.on('GET', '/exercise-sets/set1', {
      status: 200,
      data: {
        id: 'set1',
        languageCode: 'de',
        source: 'WEAK_SPOTS',
        status: 'GENERATING',
        request: {
          grammarTopicIds: [],
          types: [],
          count: 10,
          difficulty: null,
          vocabularyItemIds: [],
          combined: false,
          focusMistakes: [],
        },
        exercises: [],
        createdAt: '2026-11-13T09:00:00Z',
      },
    })
    await renderApp('/dashboard')

    const quick = await screen.findByRole('region', { name: 'Practise now' })
    const lesson = await within(quick).findByRole('button', {
      name: /Current lesson/,
    })
    expect(lesson).toHaveTextContent('No lesson yet')
    expect(lesson).toBeDisabled()

    await user.click(within(quick).getByRole('button', { name: /Weak spots/ }))
    expect(
      await screen.findByRole('heading', { name: 'Writing your exercises…' }),
    ).toBeInTheDocument()
    expect(fakeApi.requests('/progress/weak-spots/exercises')).toHaveLength(1)
  })

  it('has friendly empty states', async () => {
    await renderApp('/dashboard')
    expect(
      await screen.findByText('No practice sessions yet. Start one above.'),
    ).toBeInTheDocument()
    expect(await screen.findByText(/Nothing weak yet/)).toBeInTheDocument()
    expect(await screen.findByText('All caught up.')).toBeInTheDocument()
  })

  it("says when practice can't start", async () => {
    const user = userEvent.setup()
    fakeApi.on('POST', '/progress/weak-spots/exercises', {
      status: 409,
      data: {
        ...problem(409, 'NOTHING_TO_PRACTISE'),
        detail: 'No weak grammar topics yet.',
      },
    })
    await renderApp('/dashboard')
    const quick = await screen.findByRole('region', { name: 'Practise now' })
    await user.click(within(quick).getByRole('button', { name: /Weak spots/ }))
    const alert = await findAlert()
    expect(alert).toHaveTextContent("Couldn't start practising")
    expect(alert).toHaveTextContent('No weak grammar topics yet.')
  })
})
