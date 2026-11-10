import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchHealth } from '@/lib/health'
import { fakeApi, problem } from '@/test/fakeApi'
import { renderRoute, testUser } from '@/test/renderWithProviders'
import { findAlert } from '@/test/queries'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

const tokenResponse = {
  accessToken: 'new-session',
  tokenType: 'Bearer',
  expiresIn: 900,
  expiresAt: '2026-11-10T10:15:00Z',
}

async function openLogin(path = '/login', state?: unknown) {
  const view = renderRoute(path, { session: 'anonymous' })
  if (state !== undefined) {
    await view.router.navigate('/login', { state })
  }
  await screen.findByRole('heading', { name: 'Log in' })
  return view
}

async function fillAndSubmit(email: string, password: string) {
  if (email) await userEvent.type(screen.getByLabelText('Email'), email)
  if (password) {
    await userEvent.type(screen.getByLabelText('Password'), password)
  }
  await userEvent.click(screen.getByRole('button', { name: 'Log in' }))
}

describe('login page', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
    fakeApi.on('GET', '/users/me', { status: 200, data: testUser })
  })

  it('validates the form before calling the server', async () => {
    await openLogin()

    await fillAndSubmit('not-an-email', '')

    expect(
      await screen.findByText('Enter a valid email address'),
    ).toBeInTheDocument()
    expect(screen.getByText('Enter your password')).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveAttribute(
      'aria-invalid',
      'true',
    )
    expect(fakeApi.requests('/auth/login')).toHaveLength(0)
  })

  it('logs in and goes to the dashboard', async () => {
    fakeApi.on('POST', '/auth/login', (config) => {
      expect(JSON.parse(String(config.data))).toEqual({
        email: 'anna@example.com',
        password: 'correct-horse-1',
      })
      return { status: 200, data: tokenResponse }
    })
    const { router } = await openLogin()

    await fillAndSubmit('  anna@example.com ', 'correct-horse-1')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Dashboard' }),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/dashboard')
  })

  it('returns to the page the user wanted before logging in', async () => {
    fakeApi.on('POST', '/auth/login', { status: 200, data: tokenResponse })
    const { router } = renderRoute('/grammar?topic=dativ', {
      session: 'anonymous',
    })
    await screen.findByRole('heading', { name: 'Log in' })

    await fillAndSubmit('anna@example.com', 'correct-horse-1')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Grammar' }),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/grammar')
    expect(router.state.location.search).toBe('?topic=dativ')
  })

  it('ignores a redirect target that leaves the app', async () => {
    fakeApi.on('POST', '/auth/login', { status: 200, data: tokenResponse })
    const { router } = await openLogin('/login', {
      from: '//evil.example.com',
    })

    await fillAndSubmit('anna@example.com', 'correct-horse-1')

    await waitFor(() =>
      expect(router.state.location.pathname).toBe('/dashboard'),
    )
  })

  it('shows wrong credentials and clears the password', async () => {
    fakeApi.on('POST', '/auth/login', {
      status: 401,
      data: problem(401, 'INVALID_CREDENTIALS'),
    })
    await openLogin()

    await fillAndSubmit('anna@example.com', 'wrong-password')

    expect(await findAlert()).toHaveTextContent('Wrong email or password.')
    expect(screen.getByLabelText('Password')).toHaveValue('')
    expect(screen.getByLabelText('Password')).toHaveFocus()
    expect(fakeApi.requests('/auth/refresh')).toHaveLength(1) // only the startup restore
  })

  it('explains rate limiting with the wait time', async () => {
    fakeApi.on('POST', '/auth/login', {
      status: 429,
      data: problem(429, 'TOO_MANY_REQUESTS'),
      headers: { 'retry-after': '42' },
    })
    await openLogin()

    await fillAndSubmit('anna@example.com', 'correct-horse-1')

    expect(await findAlert()).toHaveTextContent(
      'Too many attempts. Try again in 42 seconds.',
    )
  })

  it('reports a disabled account', async () => {
    fakeApi.on('POST', '/auth/login', {
      status: 403,
      data: problem(403, 'ACCOUNT_DISABLED'),
    })
    await openLogin()

    await fillAndSubmit('anna@example.com', 'correct-horse-1')

    expect(await findAlert()).toHaveTextContent(
      'This account has been disabled.',
    )
  })

  it('reports an unreachable server', async () => {
    fakeApi.on('POST', '/auth/login', { status: 0 })
    await openLogin()

    await fillAndSubmit('anna@example.com', 'correct-horse-1')

    expect(await findAlert()).toHaveTextContent(
      "Can't reach LinguaFlow right now.",
    )
  })

  it('sends a logged-in user straight to the dashboard', async () => {
    const { router } = renderRoute('/login')
    await waitFor(() =>
      expect(router.state.location.pathname).toBe('/dashboard'),
    )
  })

  it('links to registration, keeping the redirect target', async () => {
    const { router } = renderRoute('/vocabulary', { session: 'anonymous' })
    await screen.findByRole('heading', { name: 'Log in' })

    await userEvent.click(
      screen.getByRole('link', { name: 'Create an account' }),
    )

    expect(router.state.location.pathname).toBe('/register')
    expect(router.state.location.state).toEqual({ from: '/vocabulary' })
  })
})
