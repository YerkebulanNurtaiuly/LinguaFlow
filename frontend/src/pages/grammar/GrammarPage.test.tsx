import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CatalogEntry, GrammarTopic } from '@/grammar/grammarApi'
import { fetchHealth } from '@/lib/health'
import { fakeApi, problem, sentBody } from '@/test/fakeApi'
import { renderApp } from '@/test/renderWithProviders'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

let nextId = 1

function topic(overrides: Partial<GrammarTopic> = {}): GrammarTopic {
  return {
    id: `topic-${nextId++}`,
    title: 'Dativ',
    description: 'Indirect objects',
    personalNotes: undefined,
    cefrLevel: 'A2',
    status: 'LEARNING',
    catalogTopicId: undefined,
    createdAt: '2026-11-01T10:00:00Z',
    updatedAt: '2026-11-01T10:00:00Z',
    version: 0,
    ...overrides,
  }
}

function entry(overrides: Partial<CatalogEntry> = {}): CatalogEntry {
  return {
    id: `catalog-${nextId++}`,
    slug: 'dativ',
    title: 'Dativ',
    description: 'Indirect objects and dative prepositions.',
    cefrLevel: 'A2',
    topicId: undefined,
    ...overrides,
  }
}

function topicsReply(topics: GrammarTopic[]) {
  return {
    status: 200,
    data: {
      content: topics,
      page: 0,
      size: 100,
      totalElements: topics.length,
      totalPages: topics.length ? 1 : 0,
    },
  }
}

const params = (index = -1) =>
  new URLSearchParams(fakeApi.requests('/grammar-topics').at(index)?.query)

async function openGrammar(path = '/grammar') {
  const view = await renderApp(path)
  await screen.findByRole('heading', { name: 'Grammar', level: 1 })
  return view
}

describe('grammar page', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
  })

  it('groups the topics by level, easiest first', async () => {
    fakeApi.on(
      'GET',
      '/grammar-topics',
      topicsReply([
        topic({ title: 'Akkusativ', cefrLevel: 'A1', status: 'LEARNED' }),
        topic({ title: 'Präsens', cefrLevel: 'A1', status: 'MASTERED' }),
        topic({ title: 'Dativ' }),
        topic({ title: 'Eigenes Thema', cefrLevel: undefined, status: 'NEW' }),
      ]),
    )
    await openGrammar()

    const headings = await screen.findAllByRole('heading', { level: 2 })
    expect(headings.map((h) => h.textContent)).toEqual([
      'A1 · Beginner2 topics',
      'A2 · Elementary1 topic',
      'No level set1 topic',
    ])
    const a1 = screen.getByRole('region', { name: /A1 · Beginner/ })
    expect(within(a1).getByText('Akkusativ')).toBeInTheDocument()
    expect(within(a1).getByText('Mastered')).toBeInTheDocument()
    expect(screen.getByText("4 topics you're working on.")).toBeInTheDocument()
    expect(params().getAll('sort')).toEqual(['cefrLevel,asc', 'title,asc'])
    expect(params().get('size')).toBe('100')
  })

  it('loads every page of topics', async () => {
    fakeApi.on('GET', '/grammar-topics', (config) => {
      const page = Number((config.params as URLSearchParams).get('page'))
      return {
        status: 200,
        data: {
          content: [topic({ title: `Thema ${page + 1}` })],
          page,
          size: 100,
          totalElements: 101,
          totalPages: 2,
        },
      }
    })
    await openGrammar()
    expect(await screen.findByText('Thema 2')).toBeInTheDocument()
    expect(screen.getByText('Thema 1')).toBeInTheDocument()
  })

  it('filters through the URL', async () => {
    fakeApi.on('GET', '/grammar-topics', topicsReply([topic()]))
    const { router } = await openGrammar('/grammar?status=LEARNING')
    await screen.findByText('Dativ')
    expect(params().get('status')).toBe('LEARNING')

    await userEvent.selectOptions(screen.getByLabelText('Level'), 'A2')
    await waitFor(() => expect(params().get('cefrLevel')).toBe('A2'))
    expect(router.state.location.search).toBe('?status=LEARNING&cefrLevel=A2')

    await userEvent.type(screen.getByLabelText('Search'), 'dat')
    await waitFor(() => expect(params().get('q')).toBe('dat'))
  })

  it('offers to clear filters when nothing matches', async () => {
    fakeApi.on('GET', '/grammar-topics', (config) =>
      (config.params as URLSearchParams).get('status')
        ? topicsReply([])
        : topicsReply([topic()]),
    )
    const { router } = await openGrammar('/grammar?status=MASTERED')
    expect(await screen.findByText('No topics match')).toBeInTheDocument()

    await userEvent.click(
      screen.getAllByRole('button', { name: 'Clear filters' })[0]!,
    )
    expect(await screen.findByText('Dativ')).toBeInTheDocument()
    expect(router.state.location.search).toBe('')
  })

  describe('adding topics', () => {
    it('suggests catalog topics and adds one', async () => {
      let added = false
      fakeApi.on('GET', '/grammar-topics/catalog', () => ({
        status: 200,
        data: [
          entry({
            id: 'cat-akk',
            title: 'Akkusativ',
            cefrLevel: 'A1',
            topicId: 'topic-x',
          }),
          entry({ id: 'cat-dat', topicId: added ? 'topic-new' : undefined }),
        ],
      }))
      fakeApi.on('POST', '/grammar-topics/catalog/cat-dat', () => {
        added = true
        return { status: 201, data: topic({ id: 'topic-new' }) }
      })
      await openGrammar()
      await userEvent.click(
        await screen.findByRole('button', { name: 'Browse suggestions' }),
      )

      const dialog = screen.getByRole('dialog', { name: 'Add grammar topics' })
      expect(
        within(dialog).getByRole('tab', { name: 'Suggestions' }),
      ).toHaveAttribute('aria-selected', 'true')
      const list = within(dialog)
      expect(await list.findByText('Akkusativ')).toBeInTheDocument()
      expect(list.getByText('Added')).toBeInTheDocument()

      await userEvent.click(list.getByRole('button', { name: 'Add Dativ' }))

      expect(await screen.findByText('Added “Dativ”')).toBeInTheDocument()
      await waitFor(() => expect(list.getAllByText('Added')).toHaveLength(2))
      // The page's topic list is refreshed too
      expect(fakeApi.requests('/grammar-topics').length).toBeGreaterThan(1)
    })

    it('filters the suggestions by level and text', async () => {
      fakeApi.on('GET', '/grammar-topics/catalog', {
        status: 200,
        data: [
          entry({
            title: 'Akkusativ',
            cefrLevel: 'A1',
            description: 'Direct objects.',
          }),
          entry({
            title: 'Dativ',
            cefrLevel: 'A2',
            description: 'Indirect objects.',
          }),
          entry({
            title: 'Konjunktiv II',
            cefrLevel: 'B1',
            description: 'Wishes and politeness.',
          }),
        ],
      })
      await openGrammar()
      await userEvent.click(screen.getByRole('button', { name: 'Add topic' }))
      const dialog = within(screen.getByRole('dialog'))
      await dialog.findByText('Akkusativ')

      await userEvent.selectOptions(dialog.getByLabelText('Level'), 'B1')
      expect(dialog.queryByText('Akkusativ')).not.toBeInTheDocument()
      expect(dialog.getByText('Konjunktiv II')).toBeInTheDocument()

      await userEvent.selectOptions(dialog.getByLabelText('Level'), '')
      await userEvent.type(dialog.getByLabelText('Find a suggestion'), 'dat')
      expect(dialog.getByText('Dativ')).toBeInTheDocument()
      expect(dialog.queryByText('Konjunktiv II')).not.toBeInTheDocument()
    })

    it('adds a topic of your own', async () => {
      fakeApi.on('POST', '/grammar-topics', {
        status: 201,
        data: topic({ title: 'Nebensätze mit obwohl', cefrLevel: 'B1' }),
      })
      await openGrammar()
      await userEvent.click(screen.getByRole('button', { name: 'Add topic' }))
      const dialog = within(screen.getByRole('dialog'))
      await userEvent.click(dialog.getByRole('tab', { name: 'Your own' }))

      await userEvent.type(
        dialog.getByLabelText(/Title/),
        'Nebensätze mit obwohl',
      )
      await userEvent.selectOptions(dialog.getByLabelText('Level'), 'B1')
      await userEvent.click(dialog.getByRole('button', { name: 'Add topic' }))

      expect(
        await screen.findByText('Added “Nebensätze mit obwohl”'),
      ).toBeInTheDocument()
      expect(sentBody('POST', '/grammar-topics')).toEqual({
        title: 'Nebensätze mit obwohl',
        cefrLevel: 'B1',
        status: 'NEW',
      })
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    it('says when you already have the topic', async () => {
      fakeApi.on('POST', '/grammar-topics', {
        status: 409,
        data: { ...problem(409, 'GRAMMAR_TOPIC_DUPLICATE'), existingId: 't1' },
      })
      await openGrammar()
      await userEvent.click(screen.getByRole('button', { name: 'Add topic' }))
      const dialog = within(screen.getByRole('dialog'))
      await userEvent.click(dialog.getByRole('tab', { name: 'Your own' }))
      await userEvent.type(dialog.getByLabelText(/Title/), 'dativ')
      await userEvent.click(dialog.getByRole('button', { name: 'Add topic' }))

      expect(
        await dialog.findByText('You already have “dativ”.'),
      ).toBeInTheDocument()
    })

    it('needs a title', async () => {
      await openGrammar()
      await userEvent.click(screen.getByRole('button', { name: 'Add topic' }))
      const dialog = within(screen.getByRole('dialog'))
      await userEvent.click(dialog.getByRole('tab', { name: 'Your own' }))
      await userEvent.click(dialog.getByRole('button', { name: 'Add topic' }))
      expect(await dialog.findByText('Enter a title.')).toBeInTheDocument()
    })
  })
})
