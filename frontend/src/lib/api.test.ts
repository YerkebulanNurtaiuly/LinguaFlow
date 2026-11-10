import { isAxiosError } from 'axios'
import { describe, expect, it } from 'vitest'
import { login, logout } from '@/auth/authApi'
import { tokenStore } from '@/auth/tokenStore'
import { fakeApi, problem } from '@/test/fakeApi'
import { api, refreshAccessToken } from './api'

const tokenResponse = (accessToken: string) => ({
  accessToken,
  tokenType: 'Bearer',
  expiresIn: 900,
  expiresAt: '2026-11-10T10:15:00Z',
})

const unauthorized = { status: 401, data: problem(401, 'UNAUTHORIZED') }

/** GET /words answers 200 only for `token`, like a protected endpoint with that token valid. */
function wordsAcceptOnly(token: string) {
  fakeApi.on('GET', '/words', (config) =>
    config.headers.get('Authorization') === `Bearer ${token}`
      ? { status: 200, data: ['Hund'] }
      : unauthorized,
  )
}

function refreshReturns(accessToken: string, delayMs = 0) {
  fakeApi.on('POST', '/auth/refresh', async () => {
    await new Promise((resolve) => setTimeout(resolve, delayMs))
    return { status: 200, data: tokenResponse(accessToken) }
  })
}

async function rejectionOf(promise: Promise<unknown>) {
  try {
    await promise
  } catch (error) {
    return error
  }
  throw new Error('expected the request to fail')
}

describe('API client', () => {
  it('sends the access token as a Bearer header', async () => {
    tokenStore.set('token-1')
    wordsAcceptOnly('token-1')

    const { data } = await api.get('/words')

    expect(data).toEqual(['Hund'])
    expect(fakeApi.requests('/words')[0]?.authorization).toBe('Bearer token-1')
  })

  it('sends no Authorization header without a session', async () => {
    fakeApi.on('GET', '/languages', { status: 200, data: [] })
    await api.get('/languages')
    expect(fakeApi.requests('/languages')[0]?.authorization).toBeUndefined()
  })

  it('refreshes an expired token once and retries the request', async () => {
    tokenStore.set('expired')
    wordsAcceptOnly('fresh')
    refreshReturns('fresh')

    const { data } = await api.get('/words')

    expect(data).toEqual(['Hund'])
    expect(tokenStore.get()).toBe('fresh')
    expect(fakeApi.requests('/auth/refresh')).toHaveLength(1)
    expect(fakeApi.requests('/words').map((r) => r.authorization)).toEqual([
      'Bearer expired',
      'Bearer fresh',
    ])
  })

  it('queues parallel 401s behind a single refresh', async () => {
    tokenStore.set('expired')
    wordsAcceptOnly('fresh')
    refreshReturns('fresh', 20)

    const results = await Promise.all([
      api.get('/words'),
      api.get('/words'),
      api.get('/words'),
    ])

    expect(results.map((r) => r.status)).toEqual([200, 200, 200])
    expect(fakeApi.requests('/auth/refresh')).toHaveLength(1)
  })

  it('retries without refreshing when another request already got a new token', async () => {
    tokenStore.set('expired')
    fakeApi.on('GET', '/words', (config) => {
      if (config.headers.get('Authorization') === 'Bearer fresh') {
        return { status: 200, data: ['Hund'] }
      }
      // Meanwhile another request refreshed the session
      tokenStore.set('fresh')
      return unauthorized
    })

    await api.get('/words')

    expect(fakeApi.requests('/auth/refresh')).toHaveLength(0)
  })

  it('ends the session when the refresh token is rejected', async () => {
    tokenStore.set('expired')
    wordsAcceptOnly('never')

    const error = await rejectionOf(api.get('/words'))

    expect(isAxiosError(error) && error.response?.status).toBe(401)
    expect(tokenStore.get()).toBeNull()
    expect(fakeApi.requests('/words')).toHaveLength(1)
  })

  it('gives up after one retry if the new token is rejected too', async () => {
    tokenStore.set('expired')
    wordsAcceptOnly('never')
    refreshReturns('fresh')

    const error = await rejectionOf(api.get('/words'))

    expect(isAxiosError(error) && error.response?.status).toBe(401)
    expect(fakeApi.requests('/words')).toHaveLength(2)
    expect(fakeApi.requests('/auth/refresh')).toHaveLength(1)
  })

  it('keeps the session when the refresh fails for another reason', async () => {
    tokenStore.set('expired')
    wordsAcceptOnly('never')
    fakeApi.on('POST', '/auth/refresh', {
      status: 503,
      data: problem(503, 'SERVICE_UNAVAILABLE'),
    })

    const error = await rejectionOf(api.get('/words'))

    expect(isAxiosError(error) && error.response?.status).toBe(503)
    expect(tokenStore.get()).toBe('expired')
  })

  it('does not refresh on errors other than 401', async () => {
    tokenStore.set('token-1')
    fakeApi.on('GET', '/words', {
      status: 403,
      data: problem(403, 'FORBIDDEN'),
    })

    await rejectionOf(api.get('/words'))

    expect(fakeApi.requests('/auth/refresh')).toHaveLength(0)
  })

  it('shares one refresh between parallel callers', async () => {
    refreshReturns('fresh', 20)

    const tokens = await Promise.all([
      refreshAccessToken(),
      refreshAccessToken(),
    ])

    expect(tokens).toEqual(['fresh', 'fresh'])
    expect(fakeApi.requests('/auth/refresh')).toHaveLength(1)
  })
})

describe('auth API', () => {
  it('stores the access token after login', async () => {
    fakeApi.on('POST', '/auth/login', {
      status: 200,
      data: tokenResponse('new-session'),
    })

    await login({ email: 'anna@example.com', password: 'correct-horse-1' })

    expect(tokenStore.get()).toBe('new-session')
  })

  it('reports wrong credentials without trying a refresh', async () => {
    fakeApi.on('POST', '/auth/login', {
      status: 401,
      data: problem(401, 'INVALID_CREDENTIALS'),
    })

    const error = await rejectionOf(
      login({ email: 'anna@example.com', password: 'wrong-password' }),
    )

    expect(isAxiosError(error) && error.response?.status).toBe(401)
    expect(fakeApi.requests('/auth/refresh')).toHaveLength(0)
    expect(tokenStore.get()).toBeNull()
  })

  it('ends the local session on logout even if the server is unreachable', async () => {
    tokenStore.set('token-1')
    fakeApi.on('POST', '/auth/logout', { status: 503 })

    await rejectionOf(logout())

    expect(tokenStore.get()).toBeNull()
  })
})
