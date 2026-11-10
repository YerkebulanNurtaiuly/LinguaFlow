import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchHealth } from '@/lib/health'
import { fakeApi, problem } from '@/test/fakeApi'
import { findAlert } from '@/test/queries'
import { renderApp } from '@/test/renderWithProviders'
import type { VocabularyWord } from '@/vocabulary/vocabularyApi'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

let nextId = 1

function word(overrides: Partial<VocabularyWord> = {}): VocabularyWord {
  const id = `00000000-0000-7000-8000-${String(nextId++).padStart(12, '0')}`
  return {
    id,
    lemma: 'der Hund',
    article: 'der',
    partOfSpeech: 'NOUN',
    translation: 'dog',
    plural: 'die Hunde',
    cefrLevel: 'A1',
    status: 'LEARNING',
    aiStatus: 'READY',
    tags: [],
    createdAt: '2026-11-01T10:00:00Z',
    updatedAt: '2026-11-01T10:00:00Z',
    version: 0,
    notes: undefined,
    gender: 'MASCULINE',
    aiGeneratedFields: [],
    examples: [],
    ...overrides,
  }
}

function page(content: VocabularyWord[], totalElements = content.length) {
  const totalPages = Math.ceil(totalElements / 20)
  return {
    status: 200,
    data: { content, page: 0, size: 20, totalElements, totalPages },
  }
}

const listRequests = () => fakeApi.requests('/vocabulary')
const lastQuery = () => new URLSearchParams(listRequests().at(-1)?.query ?? '')

async function openVocabulary(path = '/vocabulary') {
  const view = await renderApp(path)
  await screen.findByRole('heading', { name: 'Vocabulary', level: 1 })
  return view
}

describe('vocabulary page', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
  })

  it('lists the words with their details', async () => {
    fakeApi.on(
      'GET',
      '/vocabulary',
      page([
        word({ tags: [{ id: 't1', name: 'Tiere' }] }),
        word({
          lemma: 'laufen',
          partOfSpeech: 'VERB',
          plural: undefined,
          translation: undefined,
          cefrLevel: undefined,
          status: 'NEW',
          aiStatus: 'PENDING_AI',
        }),
      ]),
    )
    await openVocabulary()

    const table = await screen.findByRole('table')
    const rows = within(table).getAllByRole('row')
    expect(rows).toHaveLength(3)
    const dog = within(rows[1]!)
    expect(dog.getByText('der Hund')).toBeInTheDocument()
    expect(dog.getByText('die Hunde')).toBeInTheDocument()
    expect(dog.getByText('dog')).toBeInTheDocument()
    expect(dog.getByText('Noun')).toBeInTheDocument()
    expect(dog.getByText('A1')).toBeInTheDocument()
    expect(dog.getByText('Tiere')).toBeInTheDocument()
    expect(dog.queryByText('AI pending')).not.toBeInTheDocument()

    const run = within(rows[2]!)
    expect(run.getByText('AI pending')).toBeInTheDocument()
    expect(run.getByText('No translation yet')).toBeInTheDocument()

    expect(screen.getByText('2 words in your collection.')).toBeInTheDocument()
    expect(screen.getByText('1–2 of 2 words')).toBeInTheDocument()
    expect(lastQuery().get('sort')).toBe('createdAt,desc')
    expect(lastQuery().get('page')).toBe('0')
    expect(lastQuery().get('size')).toBe('20')
  })

  it('invites to add words when there are none', async () => {
    await openVocabulary()
    expect(await screen.findByText('No words yet')).toBeInTheDocument()
  })

  it('searches after a pause in typing and keeps the search in the URL', async () => {
    fakeApi.on('GET', '/vocabulary', (config) => {
      const q = (config.params as URLSearchParams).get('q')
      return q === 'strasse'
        ? page([word({ lemma: 'die Straße', translation: 'street' })])
        : page([word()])
    })
    const { router } = await openVocabulary()
    await screen.findByText('der Hund')

    await userEvent.type(screen.getByLabelText('Search'), 'strasse')

    expect(await screen.findByText('die Straße')).toBeInTheDocument()
    expect(router.state.location.search).toBe('?q=strasse')
    // One request for the whole word, not one per keystroke
    expect(listRequests().filter((r) => r.query.includes('q='))).toHaveLength(1)
  })

  it('reads filters and the page from the URL', async () => {
    fakeApi.on('GET', '/tags', {
      status: 200,
      data: [{ id: 'tag-1', name: 'Tiere', wordCount: 5 }],
    })
    fakeApi.on('GET', '/vocabulary', page([word()], 45))
    await openVocabulary(
      '/vocabulary?status=LEARNED&cefrLevel=A1&tagId=tag-1&sort=lemma,asc&page=2&status=bogus',
    )

    await screen.findByText('der Hund')
    expect(lastQuery().get('status')).toBe('LEARNED')
    expect(lastQuery().get('cefrLevel')).toBe('A1')
    expect(lastQuery().get('tagId')).toBe('tag-1')
    expect(lastQuery().get('sort')).toBe('lemma,asc')
    expect(lastQuery().get('page')).toBe('1')
    expect(screen.getByLabelText('Status')).toHaveValue('LEARNED')
    expect(await screen.findByLabelText('Tag')).toHaveValue('tag-1')
    expect(
      screen.getByRole('option', { name: 'Tiere (5)' }),
    ).toBeInTheDocument()
  })

  it('changing a filter starts again at the first page', async () => {
    fakeApi.on('GET', '/vocabulary', page([word()], 45))
    const { router } = await openVocabulary('/vocabulary?page=2')
    await screen.findByText('der Hund')

    await userEvent.selectOptions(screen.getByLabelText('Word type'), 'VERB')

    await waitFor(() =>
      expect(router.state.location.search).toBe('?partOfSpeech=VERB'),
    )
    await waitFor(() => expect(lastQuery().get('page')).toBe('0'))
    expect(lastQuery().get('partOfSpeech')).toBe('VERB')
  })

  it('pages through the list', async () => {
    fakeApi.on('GET', '/vocabulary', (config) => {
      const pageNumber = Number((config.params as URLSearchParams).get('page'))
      return {
        status: 200,
        data: {
          content: [word({ lemma: `Wort ${pageNumber + 1}` })],
          page: pageNumber,
          size: 20,
          totalElements: 45,
          totalPages: 3,
        },
      }
    })
    const { router } = await openVocabulary()
    await screen.findByText('Wort 1')
    const pagination = screen.getByRole('navigation', { name: 'Pagination' })
    expect(
      within(pagination).getByRole('button', { name: 'Previous' }),
    ).toBeDisabled()
    expect(within(pagination).getByText('Page 1 of 3')).toBeInTheDocument()

    await userEvent.click(
      within(pagination).getByRole('button', { name: 'Next' }),
    )

    expect(await screen.findByText('Wort 2')).toBeInTheDocument()
    expect(router.state.location.search).toBe('?page=2')
    expect(
      within(pagination).getByText('21–21 of 45 words'),
    ).toBeInTheDocument()
  })

  it('offers to clear filters when nothing matches', async () => {
    fakeApi.on('GET', '/vocabulary', (config) =>
      (config.params as URLSearchParams).get('q') ? page([]) : page([word()]),
    )
    const { router } = await openVocabulary('/vocabulary?q=xyz&status=NEW')

    expect(await screen.findByText('No words match')).toBeInTheDocument()
    await userEvent.click(
      screen.getAllByRole('button', { name: 'Clear filters' })[0]!,
    )

    expect(await screen.findByText('der Hund')).toBeInTheDocument()
    expect(router.state.location.search).toBe('')
    expect(screen.getByLabelText('Search')).toHaveValue('')
  })

  it('deletes a word after confirmation', async () => {
    const dog = word()
    let words = [dog, word({ lemma: 'die Katze', translation: 'cat' })]
    fakeApi.on('GET', '/vocabulary', () => page(words))
    fakeApi.on('DELETE', `/vocabulary/${dog.id}`, () => {
      words = words.filter((w) => w.id !== dog.id)
      return { status: 204 }
    })
    await openVocabulary()
    await screen.findByText('der Hund')

    await userEvent.click(
      screen.getByRole('button', { name: 'Delete der Hund' }),
    )
    const dialog = screen.getByRole('dialog', { name: 'Delete “der Hund”?' })
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Delete word' }),
    )

    expect(await screen.findByText('Deleted “der Hund”')).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.queryByText('der Hund')).not.toBeInTheDocument(),
    )
    expect(screen.getByText('die Katze')).toBeInTheDocument()
    expect(fakeApi.requests(`/vocabulary/${dog.id}`)).toHaveLength(1)
    // Tag counts may have changed
    expect(fakeApi.requests('/tags').length).toBeGreaterThan(1)
  })

  it('keeps the dialog open when deleting fails', async () => {
    const dog = word()
    fakeApi.on('GET', '/vocabulary', page([dog]))
    fakeApi.on('DELETE', `/vocabulary/${dog.id}`, {
      status: 500,
      data: problem(500, 'INTERNAL_ERROR'),
    })
    await openVocabulary()
    await userEvent.click(
      await screen.findByRole('button', { name: 'Delete der Hund' }),
    )
    await userEvent.click(screen.getByRole('button', { name: 'Delete word' }))

    expect(await findAlert()).toBeInTheDocument()
    expect(
      screen.getByRole('dialog', { name: 'Delete “der Hund”?' }),
    ).toBeInTheDocument()
  })

  it('shows a retry when the list fails to load', async () => {
    let fail = true
    fakeApi.on('GET', '/vocabulary', () =>
      fail ? { status: 0 } : page([word()]),
    )
    await openVocabulary()

    expect(
      await screen.findByText("Couldn't load your vocabulary"),
    ).toBeInTheDocument()
    fail = false
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('der Hund')).toBeInTheDocument()
  })
})
