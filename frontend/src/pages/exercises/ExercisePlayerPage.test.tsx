import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AttemptResult, Exercise } from '@/exercises/exercisesApi'
import { fetchHealth } from '@/lib/health'
import { fakeApi, problem, sentBody } from '@/test/fakeApi'
import { renderApp } from '@/test/renderWithProviders'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

const base = {
  position: 0,
  difficulty: 'EASY' as const,
  grammarTopicIds: ['t1'],
  vocabularyItemIds: [],
  reported: false,
}

const fillBlank: Exercise = {
  ...base,
  id: 'e1',
  type: 'FILL_BLANK',
  prompt: {
    instruction: undefined,
    text: 'Ich gebe ___ Mann ___ Buch.',
    hint: '(der Mann, das Buch)',
  },
}
const choice: Exercise = {
  ...base,
  id: 'e2',
  type: 'MULTIPLE_CHOICE',
  prompt: {
    instruction: 'Choose the article.',
    question: 'Ich helfe ___ Frau.',
    options: ['die', 'der', 'den'],
  },
}
const order: Exercise = {
  ...base,
  id: 'e3',
  type: 'WORD_ORDER',
  prompt: { instruction: undefined, tokens: ['dem', 'Ich', 'Hund', 'gebe'] },
}
const translation: Exercise = {
  ...base,
  id: 'e4',
  type: 'TRANSLATION',
  prompt: {
    instruction: undefined,
    direction: 'NATIVE_TO_TARGET',
    text: 'I help the man over the bridge.',
  },
}

function set(exercises: Exercise[], status = 'READY') {
  return {
    status: 200,
    data: {
      id: 's1',
      languageCode: 'de',
      source: 'MANUAL',
      status,
      request: {
        grammarTopicIds: ['t1'],
        types: [],
        count: exercises.length || 10,
        difficulty: null,
      },
      exercises: exercises.map((exercise, position) => ({
        ...exercise,
        position,
      })),
      createdAt: '2026-11-12T08:00:00Z',
    },
  }
}

function result(overrides: Partial<AttemptResult> = {}): {
  status: number
  data: AttemptResult
} {
  return {
    status: 201,
    data: {
      attempt: {
        id: 'a1',
        exerciseId: 'e1',
        correct: true,
        score: 1,
        attemptedAt: '2026-11-12T08:01:00Z',
      },
      correct: true,
      score: 1,
      evaluatedBy: 'RULES',
      notes: [],
      parts: [],
      correctAnswer: 'der',
      alternatives: [],
      explanation: 'Helfen takes the dative.',
      feedback: undefined,
      mistakes: [],
      ...overrides,
    },
  }
}

describe('exercise player', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
  })

  it('fills gaps, shows partial credit and explains the mistake', async () => {
    const user = userEvent.setup()
    fakeApi.on('GET', '/exercise-sets/s1', set([fillBlank]))
    fakeApi.on(
      'POST',
      '/exercises/e1/attempts',
      result({
        correct: false,
        score: 0.5,
        parts: [false, true],
        correctAnswer: 'Ich gebe dem Mann das Buch.',
        explanation: 'Geben takes the person in the dative.',
      }),
    )
    fakeApi.on('POST', '/exercises/e1/attempts/a1/explanation', {
      status: 200,
      data: {
        answerIsCorrect: false,
        mistake: 'You wrote "den Mann"; it must be "dem Mann".',
        rule: 'Geben takes the receiver in the dative (Dativ).',
        topic: 'Dativ',
        grammarTopicId: 't1',
        example: {
          sentence: 'Ich schenke dem Kind einen Ball.',
          translation: 'I give the child a ball.',
        },
        otherErrors: [],
        cached: false,
      },
    })
    await renderApp('/practice/exercises/s1')

    const card = await screen.findByRole('article', {
      name: 'Fill in the blank',
    })
    expect(within(card).getByText('Fill in the gaps.')).toBeInTheDocument()
    expect(
      within(card).getByText('Hint: (der Mann, das Buch)'),
    ).toBeInTheDocument()
    const check = within(card).getByRole('button', { name: 'Check' })
    expect(check).toBeDisabled()

    await user.type(within(card).getByRole('textbox', { name: 'Gap 1' }), 'den')
    await user.type(within(card).getByRole('textbox', { name: 'Gap 2' }), 'das')
    await user.click(check)

    const feedback = await screen.findByRole('region', { name: 'Result' })
    expect(within(feedback).getByRole('status')).toHaveTextContent(
      'Partly right (50%)',
    )
    expect(
      within(feedback).getByText('Ich gebe dem Mann das Buch.'),
    ).toBeInTheDocument()
    expect(
      within(feedback).getByText('Geben takes the person in the dative.'),
    ).toBeInTheDocument()
    const body = sentBody('POST', '/exercises/e1/attempts') as {
      blanks: string[]
      durationMs: number
    }
    expect(body.blanks).toEqual(['den', 'das'])
    expect(body.durationMs).toBeGreaterThanOrEqual(0)
    // Graded gaps are locked
    expect(within(card).getByRole('textbox', { name: 'Gap 1' })).toBeDisabled()

    // Focus is on the next step, so Enter would move on
    expect(screen.getByRole('button', { name: /See results/ })).toHaveFocus()

    await user.click(
      within(feedback).getByRole('button', { name: 'Why is this wrong?' }),
    )
    const explanation = await screen.findByRole('region', {
      name: 'Explanation',
    })
    expect(
      within(explanation).getByText(
        'Geben takes the receiver in the dative (Dativ).',
      ),
    ).toBeInTheDocument()

    expect(explanation).toHaveFocus()
    await user.click(screen.getByRole('button', { name: /See results/ }))
    expect(
      await screen.findByRole('heading', { name: 'Set done' }),
    ).toBeInTheDocument()
    expect(screen.getByText(/0 of 1 right · 50% overall/)).toBeInTheDocument()
  })

  it('plays choice, word order and a written answer through to the summary', async () => {
    const user = userEvent.setup()
    fakeApi.on(
      'GET',
      '/exercise-sets/s1',
      set([choice, order, translation, { ...fillBlank, reported: true }]),
    )
    fakeApi.on('POST', '/exercises/e2/attempts', result())
    fakeApi.on(
      'POST',
      '/exercises/e3/attempts',
      result({
        correct: false,
        score: 0.75,
        parts: [true, false, true, true],
        correctAnswer: 'Ich gebe dem Hund',
      }),
    )
    fakeApi.on(
      'POST',
      '/exercises/e4/attempts',
      result({
        correct: false,
        score: 0.6,
        evaluatedBy: 'AI',
        correctAnswer: 'Ich helfe dem Mann über die Brücke.',
        feedback: {
          errors: [
            {
              type: 'WRONG_CASE',
              span: 'den Mann',
              explanation: 'Helfen needs the dative.',
              grammarTopicId: 't1',
            },
          ],
          correctedAnswer: 'Ich helfe dem Mann über die Brücke.',
          explanation: 'Good word order; watch the case.',
        },
      }),
    )
    await renderApp('/practice/exercises/s1')

    // Multiple choice; the reported exercise is left out, so there are three
    expect(await screen.findByText('Exercise 1 of 3')).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: 'der' }))
    await user.click(screen.getByRole('button', { name: 'Check' }))
    expect(
      within(await screen.findByRole('region', { name: 'Result' })).getByRole(
        'status',
      ),
    ).toHaveTextContent('Right!')
    expect(sentBody('POST', '/exercises/e2/attempts')).toMatchObject({
      index: 1,
    })
    expect(screen.getByText('Right answer')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Next/ }))

    // Word order: tap words, take one back, tap again
    const sentence = await screen.findByRole('list', { name: 'Your sentence' })
    for (const word of ['Ich', 'dem', 'gebe']) {
      await user.click(screen.getByRole('button', { name: `Add “${word}”` }))
    }
    await user.click(
      within(sentence).getByRole('button', { name: 'Remove “dem”, word 2' }),
    )
    await user.click(screen.getByRole('button', { name: 'Add “dem”' }))
    // Arrow keys move a word: left, then back right
    within(sentence).getByRole('button', { name: /“dem”/ }).focus()
    await user.keyboard('{ArrowLeft}')
    expect(
      within(sentence).getByRole('button', { name: 'Remove “dem”, word 2' }),
    ).toHaveFocus()
    await user.keyboard('{ArrowRight}')
    await user.click(screen.getByRole('button', { name: 'Add “Hund”' }))
    await user.click(screen.getByRole('button', { name: 'Check' }))
    expect(
      within(await screen.findByRole('region', { name: 'Result' })).getByRole(
        'status',
      ),
    ).toHaveTextContent('Partly right (75%)')
    expect(sentBody('POST', '/exercises/e3/attempts')).toMatchObject({
      tokens: ['Ich', 'gebe', 'dem', 'Hund'],
    })
    await user.click(screen.getByRole('button', { name: /Next/ }))

    // A written answer with the umlaut keys
    const answer = await screen.findByRole('textbox', { name: 'Your answer' })
    expect(answer).toHaveFocus()
    await user.type(answer, 'Ich helfe den Mann ')
    await user.click(screen.getByRole('button', { name: 'Type ü' }))
    await user.type(answer, 'ber die Brücke.')
    expect(answer).toHaveValue('Ich helfe den Mann über die Brücke.')
    await user.keyboard('{Enter}')
    const feedback = await screen.findByRole('region', { name: 'Result' })
    expect(
      within(feedback).getByText('Good word order; watch the case.'),
    ).toBeInTheDocument()
    expect(
      within(within(feedback).getByRole('list', { name: 'Errors' })).getByText(
        'den Mann',
      ),
    ).toBeInTheDocument()
    expect(sentBody('POST', '/exercises/e4/attempts')).toMatchObject({
      text: 'Ich helfe den Mann über die Brücke.',
    })

    await user.click(screen.getByRole('button', { name: /See results/ }))
    expect(
      await screen.findByRole('heading', { name: 'Set done' }),
    ).toBeInTheDocument()
    expect(screen.getByText(/1 of 3 right · 78% overall/)).toBeInTheDocument()
    const answers = screen.getByRole('list', { name: 'Your answers' })
    expect(within(answers).getAllByRole('listitem')).toHaveLength(3)

    // Try again starts over without reloading the set
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Exercise 1 of 3')).toBeInTheDocument()
  })

  it('reports a bad exercise and takes it back', async () => {
    const user = userEvent.setup()
    fakeApi.on('GET', '/exercise-sets/s1', set([choice]))
    fakeApi.on('POST', '/exercises/e2/attempts', result())
    fakeApi.on('POST', '/exercises/e2/report', {
      status: 200,
      data: { ...choice, reported: true },
    })
    fakeApi.on('DELETE', '/exercises/e2/report', {
      status: 200,
      data: choice,
    })
    await renderApp('/practice/exercises/s1')

    await user.click(await screen.findByRole('radio', { name: 'die' }))
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await user.click(
      await screen.findByRole('button', { name: 'Report a problem' }),
    )
    const dialog = await screen.findByRole('dialog', {
      name: 'Report this exercise',
    })
    const reason = within(dialog).getByRole('textbox')
    expect(reason).toHaveFocus()
    await user.type(reason, 'Two options fit')
    await user.click(within(dialog).getByRole('button', { name: 'Report' }))

    expect(
      await screen.findByRole('button', { name: 'Reported · Undo' }),
    ).toBeInTheDocument()
    expect(sentBody('POST', '/exercises/e2/report')).toEqual({
      reason: 'Two options fit',
    })
    await user.click(screen.getByRole('button', { name: 'Reported · Undo' }))
    expect(
      await screen.findByRole('button', { name: 'Report a problem' }),
    ).toBeInTheDocument()
  })

  it('keeps the answer when checking fails', async () => {
    const user = userEvent.setup()
    fakeApi.on('GET', '/exercise-sets/s1', set([translation]))
    fakeApi.on('POST', '/exercises/e4/attempts', {
      status: 503,
      data: problem(503, 'AI_UNAVAILABLE'),
    })
    await renderApp('/practice/exercises/s1')

    const answer = await screen.findByRole('textbox', { name: 'Your answer' })
    await user.type(answer, 'Ich helfe dem Mann.{Enter}')
    expect(
      await screen.findByRole('button', { name: 'Check' }),
    ).not.toBeDisabled()
    expect(answer).toHaveValue('Ich helfe dem Mann.')
    expect(
      screen.queryByRole('region', { name: 'Result' }),
    ).not.toBeInTheDocument()
  })

  it('waits while the set is written', async () => {
    fakeApi.on('GET', '/exercise-sets/s1', set([], 'GENERATING'))
    await renderApp('/practice/exercises/s1')
    expect(
      await screen.findByRole('heading', { name: 'Writing your exercises…' }),
    ).toBeInTheDocument()
  })

  it('explains a set the AI could not write', async () => {
    fakeApi.on('GET', '/exercise-sets/s2', {
      status: 200,
      data: { ...set([], 'FAILED').data, id: 's2' },
    })
    await renderApp('/practice/exercises/s2')
    expect(
      await screen.findByText("The AI couldn't write this set"),
    ).toBeInTheDocument()
  })

  it('says when a set does not exist', async () => {
    fakeApi.on('GET', '/exercise-sets/nope', {
      status: 404,
      data: problem(404, 'NOT_FOUND'),
    })
    await renderApp('/practice/exercises/nope')
    expect(
      await screen.findByText('Exercise set not found'),
    ).toBeInTheDocument()
  })
})
