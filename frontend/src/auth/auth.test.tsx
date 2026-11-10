import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchHealth } from '@/lib/health'
import { type FakeReply, fakeApi, problem } from '@/test/fakeApi'
import { renderApp, renderRoute, testUser } from '@/test/renderWithProviders'
import { tokenStore } from './tokenStore'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

const sidebar = () => screen.getByRole('complementary')

describe('auth state and protected routes', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
  })

  it('sends anonymous visitors to the login page, remembering where they were going', async () => {
    const { router } = renderRoute('/vocabulary?page=2', {
      session: 'anonymous',
    })

    expect(
      await screen.findByRole('heading', { name: 'Log in' }),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    expect(router.state.location.state).toEqual({ from: '/vocabulary?page=2' })
  })

  it('shows a loading state while the session is being restored', async () => {
    let answer: (reply: FakeReply) => void = () => {}
    fakeApi.on(
      'POST',
      '/auth/refresh',
      () => new Promise<FakeReply>((resolve) => (answer = resolve)),
    )
    renderRoute('/dashboard', { session: 'anonymous' })

    expect(screen.getByText('Loading…')).toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: 'Dashboard' }),
    ).not.toBeInTheDocument()

    await waitFor(() =>
      expect(fakeApi.requests('/auth/refresh')).toHaveLength(1),
    )
    answer({ status: 401, data: problem(401, 'INVALID_REFRESH_TOKEN') })
    expect(
      await screen.findByRole('heading', { name: 'Log in' }),
    ).toBeInTheDocument()
  })

  it('restores the session from the refresh cookie on startup', async () => {
    fakeApi.on('POST', '/auth/refresh', {
      status: 200,
      data: {
        accessToken: 'restored',
        tokenType: 'Bearer',
        expiresIn: 900,
        expiresAt: '2026-11-10T10:15:00Z',
      },
    })
    fakeApi.on('GET', '/users/me', { status: 200, data: testUser })

    renderRoute('/grammar', { session: 'anonymous' })

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Grammar' }),
    ).toBeInTheDocument()
    expect(await within(sidebar()).findByText('Anna')).toBeInTheDocument()
    expect(fakeApi.requests('/users/me')[0]?.authorization).toBe(
      'Bearer restored',
    )
  })

  it('shows the current user in the sidebar', async () => {
    await renderApp('/dashboard')
    expect(await within(sidebar()).findByText('Anna')).toBeInTheDocument()
    expect(within(sidebar()).getByText(testUser.email)).toBeInTheDocument()
  })

  it('falls back to the email when the user has no display name', async () => {
    await renderApp('/dashboard', {
      user: { ...testUser, displayName: undefined },
    })
    expect(
      await within(sidebar()).findByText(testUser.email),
    ).toBeInTheDocument()
  })

  it('logs out: revokes the session and returns to the login page', async () => {
    fakeApi.on('POST', '/auth/logout', { status: 204 })
    const { router } = await renderApp('/dashboard')

    await userEvent.click(
      within(sidebar()).getByRole('button', { name: 'Log out' }),
    )

    expect(
      await screen.findByRole('heading', { name: 'Log in' }),
    ).toBeInTheDocument()
    expect(fakeApi.requests('/auth/logout')).toHaveLength(1)
    expect(tokenStore.get()).toBeNull()
    expect(router.state.location.pathname).toBe('/login')
  })

  it('logs out when the session expires and cannot be refreshed', async () => {
    fakeApi.on('GET', '/users/me', {
      status: 401,
      data: problem(401, 'UNAUTHORIZED'),
    })
    renderRoute('/dashboard')

    expect(
      await screen.findByRole('heading', { name: 'Log in' }),
    ).toBeInTheDocument()
    expect(fakeApi.requests('/auth/refresh')).toHaveLength(1)
  })

  it('drops cached data of the previous user when the session ends', async () => {
    const { queryClient } = await renderApp('/dashboard')
    await within(sidebar()).findByText('Anna')
    queryClient.setQueryData(['vocabulary'], ['Hund'])

    act(() => tokenStore.set(null))

    await waitFor(() =>
      expect(queryClient.getQueryData(['vocabulary'])).toBeUndefined(),
    )
  })

  it('leaves public pages reachable without a session', async () => {
    renderRoute('/register', { session: 'anonymous' })
    expect(
      await screen.findByRole('heading', { name: 'Create account' }),
    ).toBeInTheDocument()
  })
})
