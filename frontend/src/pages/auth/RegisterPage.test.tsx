import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchHealth } from '@/lib/health'
import { fakeApi, problem } from '@/test/fakeApi'
import { renderRoute, testUser } from '@/test/renderWithProviders'
import { findAlert, queryAlerts } from '@/test/queries'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

const tokenResponse = {
  accessToken: 'new-session',
  tokenType: 'Bearer',
  expiresIn: 900,
  expiresAt: '2026-11-10T10:15:00Z',
}

async function openRegister() {
  const view = renderRoute('/register', { session: 'anonymous' })
  await screen.findByRole('heading', { name: 'Create account' })
  return view
}

async function fillAndSubmit({
  name = 'Anna',
  email = 'anna@example.com',
  password = 'correct-horse-battery',
} = {}) {
  if (name) await userEvent.type(screen.getByLabelText('Your name'), name)
  if (email) await userEvent.type(screen.getByLabelText('Email'), email)
  if (password) {
    await userEvent.type(screen.getByLabelText('Password'), password)
  }
  await userEvent.click(screen.getByRole('button', { name: 'Create account' }))
}

describe('registration page', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
    fakeApi.on('GET', '/users/me', { status: 200, data: testUser })
  })

  it('checks the fields like the backend does', async () => {
    await openRegister()

    await fillAndSubmit({ name: '', email: 'anna@', password: 'short' })

    expect(await screen.findByText('Enter your name')).toBeInTheDocument()
    expect(screen.getByText('Enter a valid email address')).toBeInTheDocument()
    expect(screen.getByText('Use at least 10 characters')).toBeInTheDocument()
    expect(fakeApi.requests('/auth/register')).toHaveLength(0)
  })

  it('rejects passwords over 72 bytes, counting multi-byte characters', async () => {
    await openRegister()

    // 33 characters, but 25 Ã— "Ã¼" (2 bytes) + 8 Ã— "â‚¬" (3 bytes) = 74 UTF-8 bytes
    await fillAndSubmit({ password: 'Ã¼'.repeat(25) + 'â‚¬'.repeat(8) })

    expect(await screen.findByText('Password is too long')).toBeInTheDocument()
    expect(fakeApi.requests('/auth/register')).toHaveLength(0)
  })

  it('creates the account, logs in and goes to the dashboard', async () => {
    fakeApi.on('POST', '/auth/register', (config) => {
      expect(JSON.parse(String(config.data))).toEqual({
        displayName: 'Anna',
        email: 'anna@example.com',
        password: 'correct-horse-battery',
      })
      return { status: 201, data: {} }
    })
    fakeApi.on('POST', '/auth/login', { status: 200, data: tokenResponse })
    const { router } = await openRegister()

    await fillAndSubmit()

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Dashboard' }),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/dashboard')
    expect(screen.getByText('Welcome, Anna!')).toBeInTheDocument()
    expect(
      screen.getByText("We've sent a verification link to anna@example.com."),
    ).toBeInTheDocument()
  })

  it('flags a registered email and offers to log in instead', async () => {
    fakeApi.on('POST', '/auth/register', {
      status: 409,
      data: problem(409, 'EMAIL_TAKEN'),
    })
    const { router } = await openRegister()

    await fillAndSubmit()

    const email = screen.getByLabelText('Email')
    await waitFor(() => expect(email).toHaveAttribute('aria-invalid', 'true'))
    expect(email).toHaveFocus()
    const message = screen.getByText(
      /An account with this email already exists/,
    )
    await userEvent.click(
      within(message).getByRole('link', { name: 'Log in instead' }),
    )
    expect(router.state.location.pathname).toBe('/login')
  })

  it('shows server-side field errors next to the field', async () => {
    fakeApi.on('POST', '/auth/register', {
      status: 400,
      data: {
        ...problem(400, 'VALIDATION_FAILED'),
        errors: [{ field: 'password', message: 'is too common' }],
      },
    })
    await openRegister()

    await fillAndSubmit({ password: 'password1234' })

    expect(await screen.findByText('is too common')).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toHaveFocus()
    expect(queryAlerts()).toHaveLength(0)
  })

  it('explains rate limiting', async () => {
    fakeApi.on('POST', '/auth/register', {
      status: 429,
      data: problem(429, 'TOO_MANY_REQUESTS'),
      headers: { 'retry-after': '1800' },
    })
    await openRegister()

    await fillAndSubmit()

    expect(await findAlert()).toHaveTextContent(
      'Too many attempts. Try again in 30 minutes.',
    )
  })

  it('falls back to the login page if the automatic login fails', async () => {
    fakeApi.on('POST', '/auth/register', { status: 201, data: {} })
    fakeApi.on('POST', '/auth/login', {
      status: 429,
      data: problem(429, 'TOO_MANY_REQUESTS'),
    })
    const { router } = await openRegister()

    await fillAndSubmit()

    expect(
      await screen.findByRole('heading', { name: 'Log in' }),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    expect(screen.getByText('Account created')).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveValue('anna@example.com')
  })
})
