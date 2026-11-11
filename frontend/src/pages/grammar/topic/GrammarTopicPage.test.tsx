import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GrammarTopic, StatusChange } from '@/grammar/grammarApi'
import { fetchHealth } from '@/lib/health'
import { fakeApi, problem, sentBody } from '@/test/fakeApi'
import { renderApp } from '@/test/renderWithProviders'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

const ID = 'topic-1'
const URL = `/grammar-topics/${ID}`

function topic(overrides: Partial<GrammarTopic> = {}): GrammarTopic {
  return {
    id: ID,
    title: 'Dativ',
    description: 'Indirect objects',
    personalNotes: undefined,
    cefrLevel: 'A2',
    status: 'LEARNING',
    catalogTopicId: undefined,
    aiExplanation: undefined,
    aiExplanationGeneratedAt: undefined,
    createdAt: '2026-11-01T10:00:00Z',
    updatedAt: '2026-11-01T10:00:00Z',
    version: 1,
    ...overrides,
  }
}

const history: StatusChange[] = [
  {
    fromStatus: undefined,
    toStatus: 'NEW',
    reason: 'CREATED',
    changedAt: '2026-10-01T10:00:00Z',
  },
  {
    fromStatus: 'NEW',
    toStatus: 'LEARNING',
    reason: 'MANUAL',
    changedAt: '2026-10-05T10:00:00Z',
  },
]

/** Serves the topic and applies PATCHes to it, like the backend would. */
function serveTopic(initial: GrammarTopic) {
  let current = initial
  fakeApi.on('GET', URL, () => ({ status: 200, data: current }))
  fakeApi.on('GET', `${URL}/status-history`, { status: 200, data: history })
  fakeApi.on('PATCH', URL, (config) => {
    const body = JSON.parse(config.data as string) as Partial<GrammarTopic>
    current = { ...current, ...body, version: current.version + 1 }
    return { status: 200, data: current }
  })
}

async function openTopic(initial = topic()) {
  serveTopic(initial)
  const view = await renderApp(`/grammar/${ID}`)
  await screen.findByRole('heading', { level: 1, name: initial.title })
  return view
}

const section = (name: string) => screen.getByRole('region', { name })

describe('grammar topic page', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
  })

  it('shows the topic with its history', async () => {
    await openTopic()

    expect(screen.getByText('Indirect objects')).toBeInTheDocument()
    expect(document.title).toBe('Grammar topic · LinguaFlow')
    const status = section('How well you know it')
    expect(
      within(status).getByRole('radio', { name: /Learning/ }),
    ).toBeChecked()
    // A started topic can't go back to New
    expect(
      within(status).queryByRole('radio', { name: /^New/ }),
    ).not.toBeInTheDocument()
    expect(
      await within(status).findByText(/New → Learning/),
    ).toBeInTheDocument()
    expect(within(status).getByText('(by you)')).toBeInTheDocument()
    // No explanation yet: the learner can ask for one
    expect(
      within(section('AI explanation')).getByRole('button', {
        name: 'Explain with AI',
      }),
    ).toBeInTheDocument()
  })

  it('opens from the grammar page', async () => {
    serveTopic(topic())
    fakeApi.on('GET', '/grammar-topics', {
      status: 200,
      data: {
        content: [topic()],
        page: 0,
        size: 100,
        totalElements: 1,
        totalPages: 1,
      },
    })
    const { router } = await renderApp('/grammar')
    await userEvent.click(await screen.findByRole('link', { name: 'Dativ' }))
    expect(router.state.location.pathname).toBe(`/grammar/${ID}`)
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Dativ' }),
    ).toBeInTheDocument()
  })

  describe('status', () => {
    it('changes between learning and learned directly', async () => {
      await openTopic()
      await userEvent.click(screen.getByRole('radio', { name: /Learned/ }))

      expect(
        await screen.findByText('“Dativ” is now learned'),
      ).toBeInTheDocument()
      expect(sentBody('PATCH', URL)).toEqual({ status: 'LEARNED' })
      expect(screen.getByRole('radio', { name: /Learned/ })).toBeChecked()
    })

    it('asks before marking it mastered', async () => {
      await openTopic()
      await userEvent.click(screen.getByRole('radio', { name: /Mastered/ }))

      const dialog = screen.getByRole('dialog', {
        name: 'Mark “Dativ” as mastered?',
      })
      await userEvent.click(
        within(dialog).getByRole('button', { name: 'Cancel' }),
      )
      expect(fakeApi.requests(URL).filter((r) => r.method === 'PATCH')).toEqual(
        [],
      )
      expect(screen.getByRole('radio', { name: /Learning/ })).toBeChecked()

      await userEvent.click(screen.getByRole('radio', { name: /Mastered/ }))
      await userEvent.click(
        screen.getByRole('button', { name: 'Mark as mastered' }),
      )
      expect(
        await screen.findByText('“Dativ” is now mastered'),
      ).toBeInTheDocument()
      expect(sentBody('PATCH', URL)).toEqual({
        status: 'MASTERED',
        confirmMastered: true,
      })
    })

    it('offers New only while the topic is new', async () => {
      await openTopic(topic({ status: 'NEW' }))
      expect(screen.getByRole('radio', { name: /^New/ })).toBeChecked()
    })
  })

  describe('details', () => {
    it('saves only what changed', async () => {
      await openTopic()
      const details = within(section('Details'))
      await userEvent.clear(details.getByLabelText(/Title/))
      await userEvent.type(details.getByLabelText(/Title/), 'Der Dativ')
      await userEvent.click(
        details.getByRole('button', { name: 'Save details' }),
      )

      expect(await screen.findByText('Details saved')).toBeInTheDocument()
      expect(sentBody('PATCH', URL)).toEqual({ title: 'Der Dativ' })
      expect(
        screen.getByRole('heading', { level: 1, name: 'Der Dativ' }),
      ).toBeInTheDocument()
    })

    it('says when the title is taken', async () => {
      await openTopic()
      fakeApi.on('PATCH', URL, {
        status: 409,
        data: { ...problem(409, 'GRAMMAR_TOPIC_DUPLICATE'), existingId: 'x' },
      })
      const details = within(section('Details'))
      await userEvent.clear(details.getByLabelText(/Title/))
      await userEvent.type(details.getByLabelText(/Title/), 'Akkusativ')
      await userEvent.click(
        details.getByRole('button', { name: 'Save details' }),
      )

      expect(
        await details.findByText(
          'You already have a topic called “Akkusativ”.',
        ),
      ).toBeInTheDocument()
    })
  })

  describe('notes', () => {
    it('previews Markdown and saves the notes', async () => {
      await openTopic()
      const notes = within(section('Your notes'))
      await userEvent.type(
        notes.getByLabelText('Notes'),
        'Ich helfe **dem** Mann.',
      )
      await userEvent.click(notes.getByRole('tab', { name: 'Preview' }))

      expect((await notes.findByText('dem')).tagName).toBe('STRONG')

      await userEvent.click(notes.getByRole('button', { name: 'Save notes' }))
      expect(await screen.findByText('Notes saved')).toBeInTheDocument()
      expect(sentBody('PATCH', URL)).toEqual({
        personalNotes: 'Ich helfe **dem** Mann.',
      })
    })

    it('never renders HTML or script links from the notes', async () => {
      await openTopic(
        topic({
          personalNotes:
            '<img src=x onerror="alert(1)"><script>alert(2)</script>\n\n[click](javascript:alert(3))',
        }),
      )
      const notes = within(section('Your notes'))
      await userEvent.click(notes.getByRole('tab', { name: 'Preview' }))
      const link = await notes.findByText('click')

      const panel = notes.getByRole('tabpanel')
      expect(panel.querySelector('img, script')).toBeNull()
      expect(link.getAttribute('href') ?? '').not.toMatch(/javascript/i)
    })
  })

  const explanation = {
    summary: 'The case of the indirect object.',
    rules: ['mit, nach, bei take the dative'],
    examples: [
      { sentence: 'Ich helfe dem Mann.', translation: 'I help the man.' },
    ],
    commonMistakes: ['Using the accusative after mit.'],
  }

  it('shows the AI explanation when there is one', async () => {
    await openTopic(
      topic({
        aiExplanation: explanation,
        aiExplanationGeneratedAt: '2026-11-02T10:00:00Z',
      }),
    )
    const ai = within(section('AI explanation'))
    expect(ai.getByText('The case of the indirect object.')).toBeInTheDocument()
    expect(ai.getByText('mit, nach, bei take the dative')).toBeInTheDocument()
    expect(ai.getByText('Ich helfe dem Mann.')).toBeInTheDocument()
    expect(ai.getByText('I help the man.')).toBeInTheDocument()
    expect(ai.getByText('Using the accusative after mit.')).toBeInTheDocument()
    expect(
      ai.getByRole('button', { name: 'Write a new explanation' }),
    ).toBeInTheDocument()
  })

  describe('asking the AI for an explanation', () => {
    const job = (overrides: Record<string, unknown>) => ({
      status: 200,
      data: {
        id: 'job-1',
        type: 'GRAMMAR_EXPLANATION',
        status: 'QUEUED',
        finished: false,
        attempts: 0,
        maxAttempts: 3,
        ...overrides,
      },
    })

    it('writes one and shows it once it is ready', async () => {
      let written = false
      fakeApi.on('POST', `${URL}/explanation`, {
        status: 202,
        data: { aiJobId: 'job-1', alreadyRunning: false },
      })
      let polls = 0
      fakeApi.on('GET', '/ai/jobs/job-1', () => {
        polls += 1
        if (polls < 2) return job({})
        written = true
        return job({ status: 'SUCCEEDED', finished: true, attempts: 1 })
      })
      await openTopic()
      // The topic is fetched again once the job is done
      fakeApi.on('GET', URL, () => ({
        status: 200,
        data: written
          ? topic({
              aiExplanation: explanation,
              aiExplanationGeneratedAt: '2026-11-11T10:00:00Z',
            })
          : topic(),
      }))
      const ai = within(section('AI explanation'))

      await userEvent.click(ai.getByRole('button', { name: 'Explain with AI' }))

      expect(await ai.findByText(/Writing an explanation/)).toBeInTheDocument()
      expect(
        await ai.findByText('The case of the indirect object.', undefined, {
          timeout: 8000,
        }),
      ).toBeInTheDocument()
      expect(fakeApi.requests(`${URL}/explanation`)).toHaveLength(1)
    }, 15000)

    it('says when the AI could not write one', async () => {
      fakeApi.on('POST', `${URL}/explanation`, {
        status: 202,
        data: { aiJobId: 'job-1', alreadyRunning: false },
      })
      fakeApi.on(
        'GET',
        '/ai/jobs/job-1',
        job({ status: 'FAILED', finished: true, attempts: 1 }),
      )
      await openTopic()
      const ai = within(section('AI explanation'))

      await userEvent.click(ai.getByRole('button', { name: 'Explain with AI' }))

      expect(
        await ai.findByText("The AI couldn't write an explanation"),
      ).toBeInTheDocument()
      expect(ai.getByRole('button', { name: 'Explain with AI' })).toBeEnabled()
    })

    it('explains a wait for the daily AI allowance', async () => {
      fakeApi.on('POST', `${URL}/explanation`, {
        status: 202,
        data: { aiJobId: 'job-1', alreadyRunning: true },
      })
      fakeApi.on(
        'GET',
        '/ai/jobs/job-1',
        job({ waiting: 'DAILY_LIMIT', nextRunAt: '2026-11-12T00:00:00Z' }),
      )
      await openTopic()
      const ai = within(section('AI explanation'))

      await userEvent.click(ai.getByRole('button', { name: 'Explain with AI' }))

      expect(
        await ai.findByText(/used today's AI allowance/),
      ).toBeInTheDocument()
      expect(
        ai.queryByRole('button', { name: 'Explain with AI' }),
      ).not.toBeInTheDocument()
    })
  })

  it('deletes the topic after confirmation', async () => {
    fakeApi.on('DELETE', URL, { status: 204 })
    const { router } = await openTopic()

    await userEvent.click(screen.getByRole('button', { name: 'Delete topic' }))
    const dialog = screen.getByRole('dialog', { name: 'Delete “Dativ”?' })
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Delete topic' }),
    )

    await waitFor(() => expect(router.state.location.pathname).toBe('/grammar'))
    expect(await screen.findByText('Deleted “Dativ”')).toBeInTheDocument()
    // The deleted topic isn't refetched, so there's no "couldn't refresh" error
    await screen.findByText('No grammar topics yet')
    expect(screen.queryByText(/Couldn't refresh/)).not.toBeInTheDocument()
    expect(
      fakeApi.requests(URL).filter((r) => r.method === 'GET'),
    ).toHaveLength(1)
  })

  it('says when the topic does not exist', async () => {
    fakeApi.on('GET', URL, { status: 404, data: problem(404, 'NOT_FOUND') })
    fakeApi.on('GET', `${URL}/status-history`, {
      status: 404,
      data: problem(404, 'NOT_FOUND'),
    })
    await renderApp(`/grammar/${ID}`)
    expect(await screen.findByText('Topic not found')).toBeInTheDocument()
  })
})
