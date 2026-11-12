import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GrammarTopic } from '@/grammar/grammarApi'
import { fetchHealth } from '@/lib/health'
import { fakeApi, problem, sentBody } from '@/test/fakeApi'
import { findAlert } from '@/test/queries'
import { renderApp } from '@/test/renderWithProviders'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

function topic(id: string, title: string): GrammarTopic {
  return {
    id,
    title,
    description: undefined,
    personalNotes: undefined,
    cefrLevel: 'A2',
    status: 'LEARNING',
    catalogTopicId: undefined,
    aiExplanation: undefined,
    aiExplanationGeneratedAt: undefined,
    createdAt: '2026-11-01T10:00:00Z',
    updatedAt: '2026-11-01T10:00:00Z',
    version: 0,
  }
}

const topics = [
  topic('t1', 'Dativ'),
  topic('t2', 'Akkusativ'),
  topic('t3', 'Perfekt'),
  topic('t4', 'Präteritum'),
  topic('t5', 'Modalverben'),
  topic('t6', 'Nebensätze'),
]

const request = {
  grammarTopicIds: ['t1'],
  types: ['FILL_BLANK'],
  count: 10,
  difficulty: null,
}

function sets(content: unknown[]) {
  return {
    status: 200,
    data: {
      content,
      page: 0,
      size: 10,
      totalElements: content.length,
      totalPages: content.length ? 1 : 0,
    },
  }
}

describe('exercise set builder', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
    fakeApi.on('GET', '/grammar-topics', {
      status: 200,
      data: {
        content: topics,
        page: 0,
        size: 100,
        totalElements: topics.length,
        totalPages: 1,
      },
    })
    fakeApi.on('GET', '/exercise-sets', sets([]))
  })

  it('creates a set from the chosen topics and opens it while it is written', async () => {
    const user = userEvent.setup()
    fakeApi.on('POST', '/exercise-sets', {
      status: 202,
      data: {
        exerciseSet: {
          id: 's9',
          languageCode: 'de',
          source: 'MANUAL',
          status: 'GENERATING',
          request,
          exercises: [],
          createdAt: '2026-11-12T08:00:00Z',
        },
        aiJobId: 'j9',
      },
    })
    fakeApi.on('GET', '/exercise-sets/s9', {
      status: 200,
      data: {
        id: 's9',
        languageCode: 'de',
        source: 'MANUAL',
        status: 'GENERATING',
        request: { ...request, count: 15 },
        exercises: [],
        createdAt: '2026-11-12T08:00:00Z',
      },
    })
    await renderApp('/practice/exercises')

    await user.click(await screen.findByRole('checkbox', { name: /Dativ/ }))
    await user.click(screen.getByRole('checkbox', { name: /Perfekt/ }))
    // The default formats; untick one and add another
    expect(screen.getByRole('checkbox', { name: 'Word order' })).toBeChecked()
    await user.click(screen.getByRole('checkbox', { name: 'Word order' }))
    await user.click(screen.getByRole('checkbox', { name: 'Fix the mistake' }))
    await user.selectOptions(
      screen.getByRole('combobox', { name: 'How many' }),
      '15 exercises',
    )
    await user.click(screen.getByRole('radio', { name: /Hard/ }))
    await user.click(screen.getByRole('button', { name: 'Create exercises' }))

    expect(
      await screen.findByRole('heading', { name: 'Writing your exercises…' }),
    ).toBeInTheDocument()
    expect(sentBody('POST', '/exercise-sets')).toEqual({
      grammarTopicIds: ['t1', 't3'],
      types: [
        'FILL_BLANK',
        'MULTIPLE_CHOICE',
        'TRANSLATION',
        'ERROR_CORRECTION',
      ],
      count: 15,
      difficulty: 'HARD',
    })
  })

  it('needs a topic and a format, and allows five topics at most', async () => {
    const user = userEvent.setup()
    await renderApp('/practice/exercises')

    await screen.findByRole('checkbox', { name: /Dativ/ })
    for (const name of [
      'Fill in the blank',
      'Multiple choice',
      'Word order',
      'Translation',
    ]) {
      await user.click(screen.getByRole('checkbox', { name }))
    }
    await user.click(screen.getByRole('button', { name: 'Create exercises' }))
    expect(
      await screen.findByText('Choose at least one topic.'),
    ).toBeInTheDocument()
    expect(screen.getByText('Choose at least one format.')).toBeInTheDocument()
    expect(
      fakeApi
        .requests('/exercise-sets')
        .filter((r) => r.method.toUpperCase() === 'POST'),
    ).toHaveLength(0)

    for (const title of [
      'Dativ',
      'Akkusativ',
      'Perfekt',
      'Präteritum',
      'Modalverben',
    ]) {
      await user.click(
        screen.getByRole('checkbox', { name: new RegExp(title) }),
      )
    }
    expect(screen.getByRole('checkbox', { name: /Nebensätze/ })).toBeDisabled()
    expect(screen.getByText('(5 of up to 5)')).toBeInTheDocument()
  })

  it('preselects the topic it was opened for and searches topics', async () => {
    const user = userEvent.setup()
    await renderApp('/practice/exercises?topic=t3')

    expect(
      await screen.findByRole('checkbox', { name: /Perfekt/ }),
    ).toBeChecked()
    await user.type(
      screen.getByRole('searchbox', { name: 'Find a topic' }),
      'dat',
    )
    // The chosen topic stays visible while searching
    const boxes = screen.getAllByRole('checkbox', {
      name: /Dativ|Perfekt|Akkusativ/,
    })
    expect(boxes.map((box) => box.closest('label')?.textContent)).toEqual([
      expect.stringContaining('Dativ'),
      expect.stringContaining('Perfekt'),
    ])
  })

  it('shows a server error and lists the latest sets', async () => {
    const user = userEvent.setup()
    fakeApi.on(
      'GET',
      '/exercise-sets',
      sets([
        {
          id: 's1',
          status: 'READY',
          request: { ...request, grammarTopicIds: ['t1', 't2'] },
          exerciseCount: 9,
          createdAt: '2026-11-11T08:00:00Z',
        },
        {
          id: 's2',
          status: 'GENERATING',
          request,
          exerciseCount: 0,
          createdAt: '2026-11-12T08:00:00Z',
        },
      ]),
    )
    fakeApi.on('POST', '/exercise-sets', {
      status: 503,
      data: { ...problem(503, 'AI_UNAVAILABLE'), detail: 'Busy' },
    })
    await renderApp('/practice/exercises')

    const latest = await screen.findByRole('region', {
      name: 'Your latest sets',
    })
    const links = within(latest).getAllByRole('link')
    expect(links[0]).toHaveAttribute('href', '/practice/exercises/s1')
    expect(links[0]).toHaveTextContent('Dativ, Akkusativ')
    expect(links[0]).toHaveTextContent('9 exercises')
    expect(links[0]).toHaveTextContent('Ready')
    expect(links[1]).toHaveTextContent('Being written')

    await user.click(screen.getByRole('checkbox', { name: /Dativ/ }))
    await user.click(screen.getByRole('button', { name: 'Create exercises' }))
    expect(await findAlert()).toHaveTextContent(/./)
    expect(
      screen.getByRole('button', { name: 'Create exercises' }),
    ).toBeEnabled()
  })

  it('asks for a grammar topic first when there are none', async () => {
    fakeApi.on('GET', '/grammar-topics', {
      status: 200,
      data: {
        content: [],
        page: 0,
        size: 100,
        totalElements: 0,
        totalPages: 0,
      },
    })
    await renderApp('/practice/exercises')
    expect(
      await screen.findByText('Add a grammar topic first'),
    ).toBeInTheDocument()
  })

  it('is reached from the Practice page', async () => {
    await renderApp('/practice')
    expect(
      await screen.findByRole('link', { name: /Grammar exercises/ }),
    ).toHaveAttribute('href', '/practice/exercises')
  })
})
