import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchHealth } from '@/lib/health'
import { fakeApi, problem, sentBody } from '@/test/fakeApi'
import { renderApp } from '@/test/renderWithProviders'
import type { VocabularyWord } from '@/vocabulary/vocabularyApi'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

const DOG_ID = '00000000-0000-7000-8000-000000000001'

const dog: VocabularyWord = {
  id: DOG_ID,
  lemma: 'der Hund',
  article: 'der',
  partOfSpeech: 'NOUN',
  translation: 'dog',
  gender: 'MASCULINE',
  plural: 'die Hunde',
  cefrLevel: 'A1',
  status: 'LEARNING',
  aiStatus: 'READY',
  aiGeneratedFields: ['translation'],
  notes: undefined,
  examples: [
    {
      id: 'e1',
      sentence: 'Der Hund bellt.',
      translation: 'The dog barks.',
      aiGenerated: true,
    },
  ],
  tags: [],
  createdAt: '2026-11-01T10:00:00Z',
  updatedAt: '2026-11-01T10:00:00Z',
  version: 0,
}

const listOf = (words: VocabularyWord[]) => ({
  status: 200,
  data: {
    content: words,
    page: 0,
    size: 20,
    totalElements: words.length,
    totalPages: words.length ? 1 : 0,
  },
})

async function openAddDialog() {
  await renderApp('/vocabulary')
  await userEvent.click(await screen.findByRole('button', { name: 'Add word' }))
  return screen.getByRole('dialog', { name: 'Add a word' })
}

describe('adding a word', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
  })

  it('adds a word with only the required field', async () => {
    let words: VocabularyWord[] = []
    fakeApi.on('GET', '/vocabulary', () => listOf(words))
    fakeApi.on('POST', '/vocabulary', () => {
      words = [dog]
      return { status: 201, data: dog }
    })
    const dialog = await openAddDialog()
    expect(within(dialog).getByLabelText(/Word or phrase/)).toHaveFocus()

    await userEvent.keyboard('der Hund')
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Add word' }),
    )

    expect(await screen.findByText('Added “der Hund”')).toBeInTheDocument()
    expect(sentBody('POST', '/vocabulary')).toEqual({
      lemma: 'der Hund',
      status: 'LEARNING',
    })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(
      await screen.findByRole('button', { name: 'der Hund' }),
    ).toBeInTheDocument()
  })

  it('also opens from the empty state', async () => {
    await renderApp('/vocabulary')
    await userEvent.click(
      await screen.findByRole('button', { name: 'Add your first word' }),
    )
    expect(
      screen.getByRole('dialog', { name: 'Add a word' }),
    ).toBeInTheDocument()
  })

  it('needs the word', async () => {
    const dialog = await openAddDialog()
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Add word' }),
    )
    expect(
      await within(dialog).findByText('Enter the word or phrase.'),
    ).toBeInTheDocument()
    expect(fakeApi.requests('/vocabulary').map((r) => r.method)).not.toContain(
      'POST',
    )
  })

  it('sends the optional details, examples and tags', async () => {
    fakeApi.on('GET', '/tags', {
      status: 200,
      data: [{ id: 'tag-animals', name: 'Tiere', wordCount: 3 }],
    })
    fakeApi.on('POST', '/tags', {
      status: 201,
      data: { id: 'tag-new', name: 'Kapitel 3', wordCount: 0 },
    })
    fakeApi.on('POST', '/vocabulary', { status: 201, data: dog })
    const dialog = await openAddDialog()
    const form = within(dialog)

    await userEvent.type(form.getByLabelText(/Word or phrase/), 'der Hund')
    await userEvent.type(form.getByLabelText('Translation'), 'dog')
    await userEvent.selectOptions(form.getByLabelText('Word type'), 'NOUN')
    await userEvent.selectOptions(form.getByLabelText('Gender'), 'MASCULINE')
    await userEvent.type(form.getByLabelText('Plural'), 'die Hunde')
    await userEvent.selectOptions(form.getByLabelText('Level'), 'A1')
    await userEvent.click(await form.findByLabelText('Tiere'))
    await userEvent.type(form.getByLabelText('New tag'), 'Kapitel 3{Enter}')
    expect(await form.findByLabelText('Kapitel 3')).toBeChecked()
    await userEvent.click(form.getByRole('button', { name: 'Add example' }))
    await userEvent.type(form.getByLabelText('Example 1'), 'Der Hund bellt.')
    await userEvent.click(form.getByRole('button', { name: 'Add word' }))

    await screen.findByText('Added “der Hund”')
    expect(sentBody('POST', '/tags')).toEqual({ name: 'Kapitel 3' })
    expect(sentBody('POST', '/vocabulary')).toEqual({
      lemma: 'der Hund',
      translation: 'dog',
      partOfSpeech: 'NOUN',
      gender: 'MASCULINE',
      plural: 'die Hunde',
      cefrLevel: 'A1',
      status: 'LEARNING',
      tagIds: ['tag-animals', 'tag-new'],
      examples: [{ sentence: 'Der Hund bellt.' }],
    })
  })

  it('hides gender and plural for words that are not nouns', async () => {
    const dialog = await openAddDialog()
    await userEvent.selectOptions(
      within(dialog).getByLabelText('Word type'),
      'VERB',
    )
    expect(within(dialog).queryByLabelText('Gender')).not.toBeInTheDocument()
    expect(within(dialog).queryByLabelText('Plural')).not.toBeInTheDocument()
  })

  it('warns about a duplicate and opens the existing word', async () => {
    fakeApi.on('POST', '/vocabulary', {
      status: 409,
      data: { ...problem(409, 'VOCABULARY_DUPLICATE'), existingId: DOG_ID },
    })
    fakeApi.on('GET', `/vocabulary/${DOG_ID}`, { status: 200, data: dog })
    const dialog = await openAddDialog()

    await userEvent.type(
      within(dialog).getByLabelText(/Word or phrase/),
      'Hund',
    )
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Add word' }),
    )
    await userEvent.click(
      await within(dialog).findByRole('button', { name: 'Open “Hund”' }),
    )

    const edit = await screen.findByRole('dialog', { name: 'Edit word' })
    expect(await within(edit).findByLabelText(/Word or phrase/)).toHaveValue(
      'der Hund',
    )
  })

  it('shows server field errors next to the field', async () => {
    fakeApi.on('POST', '/vocabulary', {
      status: 400,
      data: {
        ...problem(400, 'VALIDATION_FAILED'),
        errors: [
          { field: 'tagIds', message: 'contains a tag that doesn’t exist' },
        ],
      },
    })
    const dialog = await openAddDialog()
    await userEvent.type(
      within(dialog).getByLabelText(/Word or phrase/),
      'Hund',
    )
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Add word' }),
    )
    expect(
      await within(dialog).findByText('contains a tag that doesn’t exist'),
    ).toBeInTheDocument()
  })
})

describe('editing a word', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
    fakeApi.on('GET', '/vocabulary', listOf([dog]))
    fakeApi.on('GET', `/vocabulary/${DOG_ID}`, { status: 200, data: dog })
  })

  async function openEditDialog() {
    await renderApp('/vocabulary')
    await userEvent.click(
      await screen.findByRole('button', { name: 'der Hund' }),
    )
    const dialog = screen.getByRole('dialog', { name: 'Edit word' })
    await within(dialog).findByLabelText(/Word or phrase/)
    return dialog
  }

  it('shows the word with its AI fields and examples', async () => {
    const dialog = within(await openEditDialog())

    expect(dialog.getByLabelText('Translation')).toHaveValue('dog')
    expect(dialog.getByLabelText('Translation')).toHaveAccessibleDescription(
      'Filled in by AI. Change it to use your own.',
    )
    expect(dialog.getByLabelText('Plural')).not.toHaveAccessibleDescription(
      /AI/,
    )
    expect(dialog.getByText('Der Hund bellt.')).toBeInTheDocument()
    expect(dialog.getByText('AI example')).toBeInTheDocument()
    expect(dialog.getByRole('button', { name: 'Save changes' })).toBeDisabled()
    // A set choice can't be cleared through the API, so there's no empty option
    expect(
      within(dialog.getByLabelText('Level')).queryByRole('option', {
        name: 'Not set',
      }),
    ).not.toBeInTheDocument()
  })

  it('saves only what changed', async () => {
    fakeApi.on('PATCH', `/vocabulary/${DOG_ID}`, {
      status: 200,
      data: { ...dog, translation: 'hound' },
    })
    const dialog = within(await openEditDialog())

    await userEvent.clear(dialog.getByLabelText('Translation'))
    await userEvent.type(dialog.getByLabelText('Translation'), 'hound')
    await userEvent.click(dialog.getByRole('button', { name: 'Save changes' }))

    expect(await screen.findByText('Saved “der Hund”')).toBeInTheDocument()
    expect(sentBody('PATCH', `/vocabulary/${DOG_ID}`)).toEqual({
      translation: 'hound',
    })
    await waitFor(() =>
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument(),
    )
  })
})
