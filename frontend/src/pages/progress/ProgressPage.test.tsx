import { fireEvent, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchHealth } from '@/lib/health'
import { fakeApi } from '@/test/fakeApi'
import { emptyProgressStats, renderApp } from '@/test/renderWithProviders'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

const material = (counts: [number, number, number, number]) => ({
  total: counts.reduce((a, b) => a + b, 0),
  byStatus: [
    { status: 'NEW', count: counts[0] },
    { status: 'LEARNING', count: counts[1] },
    { status: 'LEARNED', count: counts[2] },
    { status: 'MASTERED', count: counts[3] },
  ],
  byLevel: [],
  unleveled: 0,
})

const stats = {
  ...emptyProgressStats,
  words: material([12, 25, 16, 0]),
  grammarTopics: material([0, 6, 3, 1]),
  activity: [
    {
      date: '2026-11-11',
      reviews: 10,
      remembered: 8,
      exercises: 0,
      studiedMs: 120_000,
    },
    {
      date: '2026-11-12',
      reviews: 0,
      remembered: 0,
      exercises: 0,
      studiedMs: 0,
    },
    {
      date: '2026-11-13',
      reviews: 4,
      remembered: 3,
      exercises: 2,
      studiedMs: 300_000,
    },
  ],
  accuracy: [
    {
      weekStart: '2026-11-02',
      reviews: 0,
      remembered: 0,
      reviewRate: null,
      exercises: 0,
      exerciseScore: null,
    },
    {
      weekStart: '2026-11-09',
      reviews: 14,
      remembered: 11,
      reviewRate: 0.786,
      exercises: 2,
      exerciseScore: 0.5,
    },
  ],
  currentStreak: 2,
  longestStreak: 5,
  timeStudied: { todayMs: 300_000, periodMs: 420_000, totalMs: 900_000 },
}

const weakPoints = {
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
      reasons: [{ code: 'LOW_ACCURACY', value: 0.4, errorType: null }],
    },
  ],
  words: [
    {
      id: 'w1',
      name: 'der Tisch',
      translation: 'table',
      status: 'LEARNING',
      score: 0.33,
      answers: 3,
      recentAccuracy: 0.33,
      lastPracticedAt: '2026-11-12T09:00:00Z',
      reasons: [{ code: 'LAPSES', value: 3, errorType: null }],
    },
  ],
}

const mistakesByType = {
  groupBy: 'TYPE',
  since: '2026-08-15T00:00:00Z',
  total: 3,
  groups: [
    {
      errorType: 'WRONG_GAP',
      grammarTopic: null,
      count: 2,
      lastSeenAt: '2026-11-12T09:00:00Z',
      breakdown: [
        {
          errorType: null,
          grammarTopic: { id: 't1', title: 'Dativ' },
          count: 2,
        },
      ],
      examples: [
        {
          mistakeId: 'm1',
          errorType: 'WRONG_GAP',
          excerpt: 'den',
          explanation: null,
          madeAt: '2026-11-12T09:00:00Z',
          grammarTopic: { id: 't1', title: 'Dativ' },
          attempt: {
            exerciseId: 'e1',
            exerciseType: 'FILL_BLANK',
            exercise: 'Ich helfe ___ Mann.',
            answer: 'Ich helfe den Mann.',
            correctAnswer: 'Ich helfe dem Mann.',
          },
        },
      ],
    },
  ],
}

describe('progress page', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
    fakeApi.on('GET', '/progress/stats', { status: 200, data: stats })
    fakeApi.on('GET', '/progress/weak-points', {
      status: 200,
      data: weakPoints,
    })
  })

  it('shows the figures, charts and their table views', async () => {
    fakeApi.on('GET', '/progress/mistakes', {
      status: 200,
      data: mistakesByType,
    })
    await renderApp('/progress')

    const figures = await screen.findByRole('region', { name: 'Figures' })
    expect(figures).toHaveTextContent('7 min')
    expect(figures).toHaveTextContent('16')
    expect(figures).toHaveTextContent('on 2 days')
    expect(figures).toHaveTextContent('Longest: 5 days')

    expect(
      screen.getByRole('img', { name: /Practice per day/ }),
    ).toBeInTheDocument()
    // Hovering a cell shows its day
    const cells = document.querySelectorAll('rect')
    fireEvent.pointerEnter(cells[cells.length - 1]!)
    expect(screen.getByRole('tooltip')).toHaveTextContent('6 answers')
    fireEvent.pointerLeave(
      screen.getByRole('img', { name: /Practice per day/ }),
    )
    expect(screen.getByText('Show the days as a table')).toBeInTheDocument()

    // The accuracy chart answers the arrow keys
    const chart = screen.getByRole('img', { name: /Accuracy per week/ })
    chart.focus()
    fireEvent.keyDown(chart, { key: 'ArrowLeft' })
    expect(screen.getByRole('tooltip')).toHaveTextContent(
      /Week of (2 Nov|Nov 2)/,
    )
    fireEvent.keyDown(chart, { key: 'ArrowRight' })
    expect(screen.getByRole('tooltip')).toHaveTextContent('79%')
    expect(screen.getByRole('tooltip')).toHaveTextContent('50%')

    // Mastery: a segmented bar per material, every block named for screen readers
    const words = screen.getByRole('list', { name: 'Words by status' })
    expect(
      within(words).getByRole('listitem', { name: 'Learning: 25, 47%' }),
    ).toBeInTheDocument()
    // No mastered words: no block
    expect(
      within(words).queryByRole('listitem', { name: /Mastered/ }),
    ).not.toBeInTheDocument()
    const topics = screen.getByRole('list', {
      name: 'Grammar topics by status',
    })
    expect(within(topics).getAllByRole('listitem')).toHaveLength(3)
  })

  it('lists weak points with practise buttons, and frequent mistakes with own answers', async () => {
    const user = userEvent.setup()
    fakeApi.on('GET', '/progress/mistakes', (config) => ({
      status: 200,
      data:
        config.params?.groupBy === 'TOPIC'
          ? {
              ...mistakesByType,
              groupBy: 'TOPIC',
              groups: [
                {
                  ...mistakesByType.groups[0]!,
                  errorType: null,
                  grammarTopic: { id: 't1', title: 'Dativ' },
                  breakdown: [
                    { errorType: 'WRONG_GAP', grammarTopic: null, count: 2 },
                  ],
                },
              ],
            }
          : mistakesByType,
    }))
    await renderApp('/progress')

    const topics = await screen.findByRole('list', {
      name: 'Weak grammar topics',
    })
    expect(topics).toHaveTextContent('Dativ')
    expect(topics).toHaveTextContent('56% weak · 40% right lately')
    expect(
      within(topics).getByRole('link', { name: 'Practise Dativ' }),
    ).toHaveAttribute('href', '/practice/exercises?topic=t1')
    expect(screen.getByRole('list', { name: 'Weak words' })).toHaveTextContent(
      'der Tisch · table',
    )
    expect(
      screen.getByRole('button', { name: /Practise my weak spots/ }),
    ).toBeEnabled()

    const groups = await screen.findByRole('list', { name: 'Mistake groups' })
    expect(groups).toHaveTextContent('Wrong gap')
    expect(groups).toHaveTextContent('Dativ 2')
    expect(groups).toHaveTextContent('You: Ich helfe den Mann.')
    expect(groups).toHaveTextContent('Right: Ich helfe dem Mann.')

    await user.click(screen.getByRole('tab', { name: 'By topic' }))
    expect(
      await screen.findByRole('heading', { level: 3, name: 'Dativ' }),
    ).toBeInTheDocument()
    expect(fakeApi.requests('/progress/mistakes').at(-1)?.query).toContain(
      'groupBy=TOPIC',
    )
  })

  it('applies the period to the figures and mistakes', async () => {
    const user = userEvent.setup()
    await renderApp('/progress')
    await screen.findByRole('region', { name: 'Figures' })
    await user.selectOptions(screen.getByLabelText('Period'), '365')
    await screen.findByRole('region', { name: 'Figures' })
    expect(fakeApi.requests('/progress/stats').at(-1)?.query).toContain(
      'days=365',
    )
    expect(fakeApi.requests('/progress/mistakes').at(-1)?.query).toContain(
      'days=365',
    )
  })
})
