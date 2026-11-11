import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GrammarTopic } from '@/grammar/grammarApi'
import { fetchHealth } from '@/lib/health'
import type { StudySession } from '@/sessions/sessionsApi'
import { fakeApi, problem, sentBody } from '@/test/fakeApi'
import { renderApp } from '@/test/renderWithProviders'
import type { VocabularyWord } from '@/vocabulary/vocabularyApi'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

const URL = '/study-sessions/s1'

function lesson(overrides: Partial<StudySession> = {}): StudySession {
  return {
    id: 's1',
    title: 'Lektion 2',
    studiedOn: '2026-11-07',
    notes: undefined,
    active: false,
    vocabularyCount: 1,
    grammarTopicCount: 1,
    createdAt: '2026-11-07T10:00:00Z',
    updatedAt: '2026-11-07T10:00:00Z',
    version: 0,
    ...overrides,
  }
}

function word(id: string, lemma: string, translation?: string): VocabularyWord {
  return {
    id,
    lemma,
    article: undefined,
    partOfSpeech: undefined,
    translation,
    gender: undefined,
    plural: undefined,
    cefrLevel: undefined,
    status: 'LEARNING',
    aiStatus: 'READY',
    aiGeneratedFields: [],
    notes: undefined,
    examples: [],
    tags: [],
    createdAt: '2026-11-01T10:00:00Z',
    updatedAt: '2026-11-01T10:00:00Z',
    version: 0,
  }
}

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

const page = <T,>(content: T[]) => ({
  status: 200,
  data: {
    content,
    page: 0,
    size: 100,
    totalElements: content.length,
    totalPages: content.length ? 1 : 0,
  },
})

/** The lesson has "der Hund" and "Dativ"; the learner also has "die Katze" and "Akkusativ". */
function serveLesson(initial = lesson()) {
  let current = initial
  fakeApi.on('GET', URL, () => ({ status: 200, data: current }))
  fakeApi.on('PATCH', URL, (config) => {
    current = {
      ...current,
      ...(JSON.parse(config.data as string) as Partial<StudySession>),
    }
    return { status: 200, data: current }
  })
  fakeApi.on('GET', '/vocabulary', (config) =>
    (config.params as URLSearchParams).get('sessionId') === 's1'
      ? page([word('w1', 'der Hund', 'dog')])
      : page([word('w1', 'der Hund', 'dog'), word('w2', 'die Katze', 'cat')]),
  )
  fakeApi.on('GET', '/grammar-topics', (config) =>
    (config.params as URLSearchParams).get('sessionId') === 's1'
      ? page([topic('t1', 'Dativ')])
      : page([topic('t1', 'Dativ'), topic('t2', 'Akkusativ')]),
  )
}

async function openLesson(initial = lesson()) {
  serveLesson(initial)
  const view = await renderApp('/sessions/s1')
  await screen.findByRole('heading', { level: 1, name: initial.title })
  return view
}

const section = (name: string) => screen.getByRole('region', { name })

describe('lesson page', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
  })

  it('shows the lesson with its words and topics', async () => {
    await openLesson()
    expect(document.title).toBe('Lesson · LinguaFlow')
    expect(screen.getByText(/1 word · 1 topic/)).toBeInTheDocument()
    expect(
      await within(section('Words')).findByText('der Hund'),
    ).toBeInTheDocument()
    expect(within(section('Words')).getByText('dog')).toBeInTheDocument()
    expect(
      await within(section('Grammar topics')).findByRole('link', {
        name: 'Dativ',
      }),
    ).toHaveAttribute('href', '/grammar/t1')
  })

  it('removes a word from the lesson', async () => {
    fakeApi.on('DELETE', `${URL}/vocabulary/w1`, { status: 204 })
    await openLesson()
    await userEvent.click(
      await screen.findByRole('button', {
        name: 'Remove der Hund from this lesson',
      }),
    )
    expect(
      await screen.findByText('Removed “der Hund” from this lesson'),
    ).toBeInTheDocument()
    expect(fakeApi.requests(`${URL}/vocabulary/w1`)).toHaveLength(1)
  })

  it('adds words that are not in the lesson yet', async () => {
    fakeApi.on('POST', `${URL}/vocabulary`, {
      status: 200,
      data: lesson({ vocabularyCount: 2 }),
    })
    await openLesson()
    await within(section('Words')).findByText('der Hund')
    await userEvent.click(screen.getByRole('button', { name: 'Add words' }))

    const dialog = within(screen.getByRole('dialog', { name: 'Add words' }))
    const dog = await dialog.findByRole('checkbox', { name: /der Hund/ })
    expect(dog).toBeChecked()
    expect(dog).toBeDisabled()
    expect(dialog.getByText('In this lesson')).toBeInTheDocument()
    await userEvent.click(dialog.getByRole('checkbox', { name: /die Katze/ }))
    await userEvent.click(dialog.getByRole('button', { name: 'Add 1 word' }))

    expect(await screen.findByText('Added 1 word')).toBeInTheDocument()
    expect(sentBody('POST', `${URL}/vocabulary`)).toEqual({
      vocabularyItemIds: ['w2'],
    })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('searches the vocabulary when adding words', async () => {
    await openLesson()
    await userEvent.click(screen.getByRole('button', { name: 'Add words' }))
    const dialog = within(screen.getByRole('dialog', { name: 'Add words' }))
    await userEvent.type(dialog.getByLabelText('Find words'), 'katze')
    await waitFor(() =>
      expect(
        fakeApi
          .requests('/vocabulary')
          .some((r) => new URLSearchParams(r.query).get('q') === 'katze'),
      ).toBe(true),
    )
  })

  it('adds grammar topics', async () => {
    fakeApi.on('POST', `${URL}/grammar-topics`, {
      status: 200,
      data: lesson({ grammarTopicCount: 2 }),
    })
    await openLesson()
    await userEvent.click(screen.getByRole('button', { name: 'Add topics' }))
    const dialog = within(
      screen.getByRole('dialog', { name: 'Add grammar topics' }),
    )
    await userEvent.click(
      await dialog.findByRole('checkbox', { name: /Akkusativ/ }),
    )
    await userEvent.click(dialog.getByRole('button', { name: 'Add 1 topic' }))

    expect(await screen.findByText('Added 1 topic')).toBeInTheDocument()
    expect(sentBody('POST', `${URL}/grammar-topics`)).toEqual({
      grammarTopicIds: ['t2'],
    })
  })

  it('makes the lesson active and ends it', async () => {
    await openLesson()
    await userEvent.click(
      screen.getByRole('button', { name: 'Make it the active lesson' }),
    )
    expect(
      await screen.findByText('“Lektion 2” is now your active lesson'),
    ).toBeInTheDocument()
    expect(sentBody('PATCH', URL)).toEqual({ active: true })

    await userEvent.click(screen.getByRole('button', { name: 'End lesson' }))
    expect(await screen.findByText('Ended “Lektion 2”')).toBeInTheDocument()
    expect(sentBody('PATCH', URL)).toEqual({ active: false })
  })

  it('saves only the changed details', async () => {
    await openLesson()
    const details = within(section('Details'))
    await userEvent.clear(details.getByLabelText('Date'))
    await userEvent.type(details.getByLabelText('Date'), '2026-11-08')
    await userEvent.click(details.getByRole('button', { name: 'Save lesson' }))

    expect(await screen.findByText('Lesson saved')).toBeInTheDocument()
    expect(sentBody('PATCH', URL)).toEqual({ studiedOn: '2026-11-08' })
  })

  it('deletes the lesson after confirmation', async () => {
    fakeApi.on('DELETE', URL, { status: 204 })
    const { router } = await openLesson()
    await userEvent.click(screen.getByRole('button', { name: 'Delete lesson' }))
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', {
        name: 'Delete lesson',
      }),
    )
    await waitFor(() =>
      expect(router.state.location.pathname).toBe('/sessions'),
    )
    expect(await screen.findByText('Deleted “Lektion 2”')).toBeInTheDocument()
    expect(screen.queryByText(/Couldn't refresh/)).not.toBeInTheDocument()
  })

  it('says when the lesson does not exist', async () => {
    fakeApi.on('GET', URL, { status: 404, data: problem(404, 'NOT_FOUND') })
    await renderApp('/sessions/s1')
    expect(await screen.findByText('Lesson not found')).toBeInTheDocument()
  })
})
