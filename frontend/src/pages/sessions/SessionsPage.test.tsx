import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchHealth } from '@/lib/health'
import type { StudySession } from '@/sessions/sessionsApi'
import { fakeApi, sentBody } from '@/test/fakeApi'
import { renderApp } from '@/test/renderWithProviders'
import { today } from './sessionFormat'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

function lesson(overrides: Partial<StudySession> = {}): StudySession {
  return {
    id: 's1',
    title: 'Lektion 2',
    studiedOn: '2026-11-07',
    notes: undefined,
    active: false,
    vocabularyCount: 21,
    grammarTopicCount: 3,
    createdAt: '2026-11-07T10:00:00Z',
    updatedAt: '2026-11-07T10:00:00Z',
    version: 0,
    ...overrides,
  }
}

const timeline = {
  status: 200,
  data: {
    months: [
      {
        month: '2026-11',
        sessionCount: 1,
        vocabularyCount: 21,
        grammarTopicCount: 3,
        sessions: [lesson({ active: true })],
      },
      {
        month: '2026-10',
        sessionCount: 1,
        vocabularyCount: 15,
        grammarTopicCount: 3,
        sessions: [
          lesson({
            id: 's0',
            title: 'Lektion 1',
            studiedOn: '2026-10-26',
            vocabularyCount: 15,
          }),
        ],
      },
    ],
    sessionCount: 2,
    vocabularyCount: 36,
    grammarTopicCount: 6,
  },
}

async function openSessions() {
  const view = await renderApp('/sessions')
  await screen.findByRole('heading', { level: 1, name: 'Study sessions' })
  return view
}

describe('study sessions page', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
  })

  it('shows the lessons by month', async () => {
    fakeApi.on('GET', '/study-sessions/timeline', timeline)
    await openSessions()

    const november = await screen.findByRole('region', {
      name: /November 2026/,
    })
    expect(
      within(november).getByRole('link', { name: 'Lektion 2' }),
    ).toHaveAttribute('href', '/sessions/s1')
    expect(within(november).getByText('Active')).toBeInTheDocument()
    // The month total and the lesson itself
    expect(within(november).getAllByText(/21 words · 3 topics/)).toHaveLength(2)
    expect(
      screen.getByRole('region', { name: /October 2026/ }),
    ).toBeInTheDocument()
    expect(
      screen.getByText('2 lessons · 36 words · 6 topics'),
    ).toBeInTheDocument()
  })

  it('creates a lesson and opens it', async () => {
    fakeApi.on('POST', '/study-sessions', {
      status: 201,
      data: lesson({ id: 'new', title: 'Lektion 3', active: true }),
    })
    fakeApi.on('GET', '/study-sessions/new', {
      status: 200,
      data: lesson({ id: 'new', title: 'Lektion 3', active: true }),
    })
    fakeApi.on('GET', '/vocabulary', {
      status: 200,
      data: {
        content: [],
        page: 0,
        size: 100,
        totalElements: 0,
        totalPages: 0,
      },
    })
    const { router } = await openSessions()

    await userEvent.click(
      await screen.findByRole('button', { name: 'Create your first lesson' }),
    )
    const dialog = within(screen.getByRole('dialog', { name: 'New lesson' }))
    expect(dialog.getByLabelText(/Title/)).toHaveFocus()
    expect(dialog.getByLabelText('Date')).toHaveValue(today())
    expect(dialog.getByRole('checkbox')).toBeChecked()
    await userEvent.type(dialog.getByLabelText(/Title/), 'Lektion 3')
    await userEvent.click(dialog.getByRole('button', { name: 'Create lesson' }))

    await waitFor(() =>
      expect(router.state.location.pathname).toBe('/sessions/new'),
    )
    expect(sentBody('POST', '/study-sessions')).toEqual({
      title: 'Lektion 3',
      studiedOn: today(),
      active: true,
    })
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Lektion 3' }),
    ).toBeInTheDocument()
  })

  it('needs a title', async () => {
    await openSessions()
    await userEvent.click(screen.getByRole('button', { name: 'New lesson' }))
    const dialog = within(screen.getByRole('dialog'))
    await userEvent.click(dialog.getByRole('button', { name: 'Create lesson' }))
    expect(
      await dialog.findByText('Enter a title, e.g. Lesson 5.'),
    ).toBeInTheDocument()
  })

  it('shows the active lesson and can end it', async () => {
    fakeApi.on('GET', '/study-sessions/active', {
      status: 200,
      data: lesson({ active: true }),
    })
    fakeApi.on('PATCH', '/study-sessions/s1', {
      status: 200,
      data: lesson({ active: false }),
    })
    await openSessions()

    expect(
      await screen.findByText('“Lektion 2” is your active lesson'),
    ).toBeInTheDocument()
    fakeApi.on('GET', '/study-sessions/active', { status: 204 })
    await userEvent.click(screen.getByRole('button', { name: 'End lesson' }))

    expect(await screen.findByText('Ended “Lektion 2”')).toBeInTheDocument()
    expect(sentBody('PATCH', '/study-sessions/s1')).toEqual({ active: false })
    await waitFor(() =>
      expect(
        screen.queryByText('“Lektion 2” is your active lesson'),
      ).not.toBeInTheDocument(),
    )
  })
})
