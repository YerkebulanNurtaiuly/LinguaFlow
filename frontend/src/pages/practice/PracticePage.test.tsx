import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Exercise } from '@/exercises/exercisesApi'
import { fetchHealth } from '@/lib/health'
import type {
  PracticeItem,
  PracticeOverview,
  PracticeSession,
  PracticeSummary,
} from '@/practice/practiceApi'
import type { QueueItem } from '@/reviews/reviewsApi'
import { fakeApi, problem, sentBody } from '@/test/fakeApi'
import { findAlert } from '@/test/queries'
import { renderApp } from '@/test/renderWithProviders'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

const preview = {
  state: 'LEARNING' as const,
  intervalDays: 0,
  dueAt: '2026-11-12T10:10:00Z',
}

function card(
  id: string,
  front: string,
  back: string,
): NonNullable<PracticeItem['flashcard']> {
  const item: QueueItem = {
    card: {
      id,
      vocabularyItemId: `w-${id}`,
      lemma: front,
      direction: 'TARGET_TO_NATIVE',
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
      again: preview,
      hard: preview,
      good: preview,
      easy: { state: 'REVIEW', intervalDays: 4, dueAt: '2026-11-16T00:00:00Z' },
    },
  }
  return item
}

const choice: Exercise = {
  id: 'e1',
  position: 0,
  type: 'MULTIPLE_CHOICE',
  difficulty: undefined,
  grammarTopicIds: ['t1'],
  vocabularyItemIds: [],
  reported: false,
  prompt: {
    instruction: undefined,
    question: 'Ich helfe ___ Frau.',
    options: ['die', 'der', 'den'],
  },
}

function flashcardItem(
  position: number,
  id: string,
  front: string,
  back: string,
) {
  return {
    position,
    type: 'FLASHCARD' as const,
    answeredAt: undefined,
    flashcard: card(id, front, back),
    exercise: undefined,
  }
}

function exerciseItem(position: number, exercise: Exercise) {
  return {
    position,
    type: 'EXERCISE' as const,
    answeredAt: undefined,
    flashcard: undefined,
    exercise,
  }
}

function session(overrides: Partial<PracticeSession> = {}): PracticeSession {
  return {
    id: 'p1',
    languageCode: 'de',
    mode: 'MIXED',
    status: 'IN_PROGRESS',
    studySessionId: undefined,
    config: {
      length: 2,
      includeFlashcards: true,
      includeExercises: true,
      newRatio: 0.6,
      seed: 42,
    },
    startedAt: '2026-11-12T10:00:00Z',
    completedAt: undefined,
    items: [flashcardItem(0, 'c1', 'der Hund', 'dog'), exerciseItem(1, choice)],
    exerciseSet: { id: 's1', status: 'READY', requested: 1 },
    summary: undefined,
    ...overrides,
  }
}

const summary: PracticeSummary = {
  items: 2,
  answeredItems: 2,
  flashcards: {
    items: 1,
    answered: 1,
    remembered: 0,
    again: 1,
    hard: 0,
    good: 0,
    easy: 0,
  },
  exercises: { items: 1, answered: 1, correct: 1, averageScore: 1 },
  durationMs: 95_000,
  missedFlashcardIds: ['c1'],
  missedExerciseIds: [],
}

const overview: PracticeOverview = {
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
  practiceSessionLength: 20,
  inProgress: undefined,
}

describe('practice hub', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
  })

  it('shows what each mode uses and starts a session', async () => {
    const user = userEvent.setup()
    fakeApi.on('GET', '/practice-sessions/overview', {
      status: 200,
      data: overview,
    })
    fakeApi.on('POST', '/practice-sessions', {
      status: 201,
      data: { practiceSession: session(), aiJobId: 'j1' },
    })
    fakeApi.on('GET', '/practice-sessions/p1', { status: 200, data: session() })
    await renderApp('/practice')

    const modes = await screen.findByRole('group', { name: 'What to practise' })
    expect(
      within(modes).getByRole('radio', { name: /Current lesson/ }),
    ).toBeChecked()
    expect(modes).toHaveTextContent('Lektion 5: 8 words, 2 grammar topics')
    expect(modes).toHaveTextContent(
      '12 new and 41 older words, 10 grammar topics',
    )
    expect(modes).toHaveTextContent('53 words, 10 grammar topics')

    await user.click(within(modes).getByRole('radio', { name: /Mixed/ }))
    await user.selectOptions(screen.getByLabelText(/Length/), '10')
    await user.click(screen.getByRole('checkbox', { name: /Exercises/ }))
    await user.click(screen.getByRole('button', { name: 'Start' }))

    // The player opens
    await screen.findByRole('region', { name: 'Question' })
    expect(sentBody('POST', '/practice-sessions')).toEqual({
      mode: 'MIXED',
      length: 10,
      includeFlashcards: true,
      includeExercises: false,
    })
  })

  it('explains what is missing', async () => {
    const user = userEvent.setup()
    fakeApi.on('GET', '/practice-sessions/overview', {
      status: 200,
      data: {
        ...overview,
        currentLesson: undefined,
        inProgress: {
          id: 'p9',
          mode: 'FULL_REVISION',
          status: 'IN_PROGRESS',
          studySessionId: undefined,
          startedAt: '2026-11-12T09:00:00Z',
          completedAt: undefined,
          items: 20,
          answered: 7,
        },
      },
    })
    fakeApi.on('POST', '/practice-sessions', {
      status: 409,
      data: {
        ...problem(409, 'NOTHING_TO_PRACTISE'),
        detail: 'There’s nothing to practise yet.',
      },
    })
    await renderApp('/practice')

    const progress = await screen.findByRole('region', {
      name: 'Session in progress',
    })
    expect(progress).toHaveTextContent('Full revision in progress')
    expect(progress).toHaveTextContent('7 of 20 items done')
    expect(
      within(progress).getByRole('link', { name: /Continue/ }),
    ).toHaveAttribute('href', '/practice/sessions/p9')

    const modes = screen.getByRole('group', { name: 'What to practise' })
    expect(modes).toHaveTextContent('No lesson yet')
    // Mixed practice is preselected without a lesson
    expect(within(modes).getByRole('radio', { name: /Mixed/ })).toBeChecked()

    await user.click(
      within(modes).getByRole('radio', { name: /Current lesson/ }),
    )
    expect(screen.getByRole('button', { name: 'Start' })).toBeDisabled()
    expect(screen.getByText(/There’s no lesson yet/)).toBeInTheDocument()

    await user.click(
      within(modes).getByRole('radio', { name: /Full revision/ }),
    )
    await user.click(screen.getByRole('checkbox', { name: /Flashcards/ }))
    await user.click(screen.getByRole('checkbox', { name: /Exercises/ }))
    expect(screen.getByRole('button', { name: 'Start' })).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: /Flashcards/ }))
    await user.click(screen.getByRole('button', { name: 'Start' }))
    expect(await findAlert()).toHaveTextContent(
      'There’s nothing to practise yet.',
    )
  })
})

describe('practice session player', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
  })

  it('plays cards and exercises, then shows the summary and retries mistakes', async () => {
    const user = userEvent.setup()
    fakeApi.on('GET', '/practice-sessions/p1', { status: 200, data: session() })
    fakeApi.on('POST', '/reviews', {
      status: 201,
      data: {
        review: {
          id: 'r1',
          flashcardId: 'c1',
          grade: 'AGAIN',
          reviewedAt: '2026-11-12T10:00:30Z',
          durationMs: 3000,
          stateBefore: 'NEW',
          intervalBefore: 0,
          intervalAfter: 0,
          easeBefore: 2.5,
          easeAfter: 2.5,
        },
        card: card('c1', 'der Hund', 'dog').card,
        replayed: false,
      },
    })
    fakeApi.on('POST', '/exercises/e1/attempts', {
      status: 201,
      data: {
        attempt: {
          id: 'a1',
          exerciseId: 'e1',
          correct: true,
          score: 1,
          attemptedAt: '2026-11-12T10:01:00Z',
        },
        correct: true,
        score: 1,
        evaluatedBy: 'RULES',
        notes: [],
        parts: [],
        correctAnswer: 'der',
        alternatives: [],
        explanation: null,
        feedback: null,
        mistakes: [],
      },
    })
    const completed = session({
      status: 'COMPLETED',
      completedAt: '2026-11-12T10:02:00Z',
      summary,
    })
    fakeApi.on('POST', '/practice-sessions/p1/complete', {
      status: 200,
      data: completed,
    })
    fakeApi.on('POST', '/practice-sessions/p1/retry', {
      status: 201,
      data: {
        practiceSession: session({
          id: 'p2',
          items: [flashcardItem(0, 'c1', 'der Hund', 'dog')],
          exerciseSet: undefined,
        }),
        aiJobId: null,
      },
    })
    fakeApi.on('GET', '/practice-sessions/p2', {
      status: 200,
      data: session({
        id: 'p2',
        items: [flashcardItem(0, 'c1', 'der Hund', 'dog')],
        exerciseSet: undefined,
      }),
    })
    await renderApp('/practice/sessions/p1')

    expect(await screen.findByText('0 of 2 done')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Show answer/ }))
    await user.click(await screen.findByRole('button', { name: /^Again/ }))
    const review = sentBody('POST', '/reviews') as Record<string, unknown>
    expect(review).toMatchObject({
      flashcardId: 'c1',
      grade: 'AGAIN',
      practiceSessionId: 'p1',
    })

    // Then the exercise
    expect(await screen.findByText('Ich helfe ___ Frau.')).toBeInTheDocument()
    expect(screen.getByText('1 of 2 done')).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: 'der' }))
    await user.click(screen.getByRole('button', { name: 'Check' }))
    expect(sentBody('POST', '/exercises/e1/attempts')).toMatchObject({
      index: 1,
      practiceSessionId: 'p1',
    })
    await user.click(await screen.findByRole('button', { name: /See results/ }))

    // Completed: the summary
    expect(
      await screen.findByRole('heading', { name: 'Session done' }),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/2 of 2 items answered · 2 min/),
    ).toBeInTheDocument()
    expect(
      screen.getByText('Flashcards remembered').parentElement,
    ).toHaveTextContent('0 of 1')
    expect(screen.getByText('Exercises right').parentElement).toHaveTextContent(
      '1 of 1',
    )
    const missed = screen.getByRole('region', { name: 'To practise again' })
    expect(missed).toHaveTextContent('der Hund → dog')

    await user.click(
      screen.getByRole('button', { name: 'Practise mistakes again (1)' }),
    )
    await waitFor(() =>
      expect(fakeApi.requests('/practice-sessions/p2')).not.toHaveLength(0),
    )
    expect(await screen.findByText('0 of 1 done')).toBeInTheDocument()
  })

  it('waits for exercises still being written', async () => {
    fakeApi.on('GET', '/practice-sessions/p1', {
      status: 200,
      data: session({
        items: [
          {
            ...flashcardItem(0, 'c1', 'der Hund', 'dog'),
            answeredAt: '2026-11-12T10:00:30Z',
          },
        ],
        exerciseSet: { id: 's1', status: 'GENERATING', requested: 3 },
      }),
    })
    await renderApp('/practice/sessions/p1')

    expect(
      await screen.findByRole('heading', { name: 'Writing your exercises…' }),
    ).toBeInTheDocument()
    expect(screen.getByText('1 of 4 done')).toBeInTheDocument()
    expect(fakeApi.requests('/practice-sessions/p1/complete')).toHaveLength(0)
  })

  it('shows a missing session', async () => {
    fakeApi.on('GET', '/practice-sessions/p404', {
      status: 404,
      data: problem(404, 'NOT_FOUND'),
    })
    await renderApp('/practice/sessions/p404')
    expect(
      await screen.findByText('Practice session not found'),
    ).toBeInTheDocument()
  })
})
