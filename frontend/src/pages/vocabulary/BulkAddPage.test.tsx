import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchHealth } from '@/lib/health'
import { fakeApi, problem, sentBody } from '@/test/fakeApi'
import { renderApp } from '@/test/renderWithProviders'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

const preview = {
  status: 200,
  data: {
    lines: [
      {
        line: 1,
        text: 'der Hund - dog',
        status: 'NEW',
        lemma: 'der Hund',
        translation: 'dog',
      },
      {
        line: 2,
        text: 'die Katze - cat',
        status: 'NEW',
        lemma: 'die Katze',
        translation: 'cat',
      },
      {
        line: 3,
        text: 'Brot',
        status: 'DUPLICATE',
        lemma: 'Brot',
        existingId: 'w-bread',
        message: 'already in your vocabulary',
      },
      { line: 5, text: '- leer', status: 'INVALID', message: 'has no word' },
    ],
    newCount: 2,
    duplicateCount: 1,
    invalidCount: 1,
  },
}

/** Answers the "words waiting for AI" poll with `count`. */
function pendingAi(count: number) {
  fakeApi.on('GET', '/vocabulary', (config) => {
    const params = config.params as URLSearchParams
    expect(params.get('aiStatus')).toBe('PENDING_AI')
    return {
      status: 200,
      data: {
        content: [],
        page: 0,
        size: 1,
        totalElements: count,
        totalPages: count,
      },
    }
  })
}

async function openBulkAdd() {
  const view = await renderApp('/vocabulary/add')
  await screen.findByRole('heading', { name: 'Add many words', level: 1 })
  return view
}

async function previewList(text = 'der Hund - dog') {
  fakeApi.on('POST', '/vocabulary/bulk', preview)
  await openBulkAdd()
  await userEvent.type(screen.getByLabelText('Your words'), text)
  await userEvent.click(screen.getByRole('button', { name: 'Check the list' }))
  return screen.findByRole('table')
}

describe('adding many words', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
  })

  it('is reachable from the vocabulary page', async () => {
    const { router } = await renderApp('/vocabulary')
    await userEvent.click(await screen.findByRole('link', { name: 'Add many' }))
    expect(router.state.location.pathname).toBe('/vocabulary/add')
    expect(
      await screen.findByRole('heading', { name: 'Add many words' }),
    ).toBeInTheDocument()
  })

  it('previews every line and preselects only the new ones', async () => {
    const table = await previewList()

    expect(sentBody('POST', '/vocabulary/bulk')).toEqual({
      text: 'der Hund - dog',
      format: 'TEXT',
    })
    expect(
      screen.getByText(
        "2 new words · 1 already in your vocabulary · 1 line can't be added",
      ),
    ).toBeInTheDocument()
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(4)
    expect(within(rows[0]!).getByRole('checkbox')).toBeChecked()
    expect(within(rows[2]!).getByRole('checkbox')).toBeDisabled()
    expect(within(rows[2]!).getByText('Already added')).toBeInTheDocument()
    expect(
      within(rows[2]!).getByText('already in your vocabulary'),
    ).toBeInTheDocument()
    expect(within(rows[3]!).getByText('has no word')).toBeInTheDocument()
    expect(within(rows[3]!).getByText('5')).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Add 2 words' }),
    ).toBeInTheDocument()
  })

  it('adds the selected lines with the chosen tags and follows the AI', async () => {
    fakeApi.on('GET', '/tags', {
      status: 200,
      data: [{ id: 'tag-1', name: 'Kapitel 3', wordCount: 0 }],
    })
    fakeApi.on('POST', '/vocabulary/bulk/confirm', {
      status: 200,
      data: {
        results: [{ index: 0, lemma: 'der Hund', status: 'CREATED', id: 'w1' }],
        createdCount: 1,
        duplicateCount: 0,
        invalidCount: 0,
      },
    })
    pendingAi(1)
    await previewList()

    await userEvent.click(
      screen.getByRole('checkbox', { name: 'Add die Katze' }),
    )
    await userEvent.click(await screen.findByLabelText('Kapitel 3'))
    await userEvent.click(screen.getByRole('button', { name: 'Add 1 word' }))

    expect(await screen.findByText('Added 1 word')).toBeInTheDocument()
    expect(sentBody('POST', '/vocabulary/bulk/confirm')).toEqual({
      items: [{ lemma: 'der Hund', translation: 'dog' }],
      tagIds: ['tag-1'],
    })
    expect(
      await screen.findByText(/1 word is waiting for translations/),
    ).toBeInTheDocument()
    expect(screen.getByText('AI is filling in the details')).toBeInTheDocument()
  })

  it('reports skipped words and when the AI is done', async () => {
    fakeApi.on('POST', '/vocabulary/bulk/confirm', {
      status: 200,
      data: {
        results: [
          { index: 0, lemma: 'der Hund', status: 'CREATED', id: 'w1' },
          {
            index: 1,
            lemma: 'die Katze',
            status: 'DUPLICATE',
            existingId: 'w2',
            message: 'already in your vocabulary',
          },
        ],
        createdCount: 1,
        duplicateCount: 1,
        invalidCount: 0,
      },
    })
    pendingAi(0)
    await previewList()
    await userEvent.click(screen.getByRole('button', { name: 'Add 2 words' }))

    expect(await screen.findByText('1 word was skipped.')).toBeInTheDocument()
    expect(screen.getByText(': already in your vocabulary')).toBeInTheDocument()
    expect(
      await screen.findByText('AI has filled in the details'),
    ).toBeInTheDocument()

    await userEvent.click(
      screen.getByRole('button', { name: 'Add more words' }),
    )
    expect(screen.getByLabelText('Your words')).toHaveValue('')
  })

  it('labels a line that repeats an earlier one', async () => {
    fakeApi.on('POST', '/vocabulary/bulk', {
      status: 200,
      data: {
        lines: [
          { line: 1, text: 'Hund', status: 'NEW', lemma: 'Hund' },
          {
            line: 2,
            text: 'hund',
            status: 'DUPLICATE',
            lemma: 'hund',
            duplicateOfLine: 1,
            message: 'repeats line 1',
          },
        ],
        newCount: 1,
        duplicateCount: 1,
        invalidCount: 0,
      },
    })
    await openBulkAdd()
    await userEvent.type(screen.getByLabelText('Your words'), 'Hund')
    await userEvent.click(
      screen.getByRole('button', { name: 'Check the list' }),
    )

    expect(await screen.findByText('Repeated')).toBeInTheDocument()
    expect(screen.getByText('repeats line 1')).toBeInTheDocument()
    expect(screen.queryByText('Already added')).not.toBeInTheDocument()
  })

  it('goes back to the list with the text kept', async () => {
    await previewList('der Hund - dog')
    await userEvent.click(screen.getByRole('button', { name: 'Edit the list' }))
    expect(screen.getByLabelText('Your words')).toHaveValue('der Hund - dog')
  })

  it('can deselect everything, which disables adding', async () => {
    await previewList()
    await userEvent.click(screen.getByRole('button', { name: 'Select none' }))
    expect(screen.getByRole('button', { name: 'Add words' })).toBeDisabled()
    await userEvent.click(
      screen.getByRole('button', { name: 'Select all new' }),
    )
    expect(
      screen.getByRole('button', { name: 'Add 2 words' }),
    ).toBeInTheDocument()
  })

  it('sends CSV when that format is chosen', async () => {
    fakeApi.on('POST', '/vocabulary/bulk', preview)
    await openBulkAdd()
    await userEvent.click(screen.getByRole('radio', { name: /CSV/ }))
    await userEvent.type(screen.getByLabelText('Your words'), 'Hund,dog')
    await userEvent.click(
      screen.getByRole('button', { name: 'Check the list' }),
    )
    await screen.findByRole('table')
    expect(sentBody('POST', '/vocabulary/bulk')).toEqual({
      text: 'Hund,dog',
      format: 'CSV',
    })
  })

  it('needs at least one word and at most 500 lines', async () => {
    await openBulkAdd()
    await userEvent.click(
      screen.getByRole('button', { name: 'Check the list' }),
    )
    expect(
      await screen.findByText('Paste at least one word.'),
    ).toBeInTheDocument()

    const textarea = screen.getByLabelText('Your words')
    await userEvent.click(textarea)
    await userEvent.paste('Wort\n'.repeat(501))
    expect(screen.getByText(/That's 501 lines/)).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Check the list' }),
    ).toBeDisabled()
    expect(fakeApi.requests('/vocabulary/bulk')).toHaveLength(0)
  })

  it('shows the server error under the text', async () => {
    fakeApi.on('POST', '/vocabulary/bulk', {
      status: 400,
      data: {
        ...problem(400, 'VALIDATION_FAILED'),
        errors: [{ field: 'text', message: 'at most 500 lines per request' }],
      },
    })
    await openBulkAdd()
    await userEvent.type(screen.getByLabelText('Your words'), 'Hund')
    await userEvent.click(
      screen.getByRole('button', { name: 'Check the list' }),
    )
    await waitFor(() =>
      expect(screen.getByLabelText('Your words')).toHaveAccessibleDescription(
        /at most 500 lines per request/,
      ),
    )
  })
})
