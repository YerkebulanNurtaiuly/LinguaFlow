import axios, { isAxiosError, type InternalAxiosRequestConfig } from 'axios'
import { z } from 'zod'
import type { TokenResponse } from '@/api/types'
import { tokenStore } from '@/auth/tokenStore'

declare module 'axios' {
  interface AxiosRequestConfig {
    /**
     * Don't try a token refresh when this request gets a 401. Set it on the auth
     * endpoints, where a 401 means wrong credentials or a dead refresh token.
     */
    skipAuthRefresh?: boolean
  }
}

/** Marks a request that was already retried after a refresh, so it is retried once at most. */
const RETRIED = Symbol('authRetried')
type RetryableConfig = InternalAxiosRequestConfig & { [RETRIED]?: boolean }

export const tokenResponseSchema = z.object({
  accessToken: z.string().min(1),
  tokenType: z.string(),
  expiresIn: z.number(),
  expiresAt: z.string(),
}) satisfies z.ZodType<TokenResponse>

/**
 * Shared HTTP client for the backend. Sends the access token as a Bearer header; on a
 * 401 it refreshes the token once (concurrent failures wait for the same refresh) and
 * retries the request. If the refresh token is rejected the session is cleared, which
 * sends the user to the login page.
 */
export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  headers: { 'Content-Type': 'application/json' },
  // The refresh cookie must travel when the API is on another origin
  withCredentials: true,
})

const bearer = (token: string) => `Bearer ${token}`

api.interceptors.request.use((config) => {
  const token = tokenStore.get()
  if (token) config.headers.set('Authorization', bearer(token))
  return config
})

api.interceptors.response.use(undefined, async (error: unknown) => {
  if (!isAxiosError(error) || error.response?.status !== 401) throw error
  const config = error.config as RetryableConfig | undefined
  if (!config || config.skipAuthRefresh || config[RETRIED]) throw error
  config[RETRIED] = true

  // Another request already refreshed while this one was in flight: just retry
  const current = tokenStore.get()
  if (current && config.headers.get('Authorization') !== bearer(current)) {
    return api(config)
  }

  const token = await refreshAccessToken()
  if (!token) throw error
  return api(config)
})

let refreshing: Promise<string | null> | null = null

/**
 * Exchanges the refresh cookie for a new access token. Calls made while a refresh is
 * running share it: the backend rotates the refresh token on every use and treats a
 * reused one as theft, so two parallel refreshes would end the session.
 *
 * Resolves to the new token, or null when there is no valid session (the store is
 * cleared). Network and server errors reject and leave the session as it is.
 */
export function refreshAccessToken(): Promise<string | null> {
  refreshing ??= withCrossTabLock(requestNewAccessToken).finally(() => {
    refreshing = null
  })
  return refreshing
}

async function requestNewAccessToken(): Promise<string | null> {
  try {
    const { data } = await api.post<unknown>('/auth/refresh', undefined, {
      skipAuthRefresh: true,
    })
    const { accessToken } = tokenResponseSchema.parse(data)
    tokenStore.set(accessToken)
    return accessToken
  } catch (error) {
    if (isAxiosError(error) && error.response?.status === 401) {
      tokenStore.set(null)
      return null
    }
    throw error
  }
}

/**
 * Tabs share the refresh cookie, so they must not refresh at the same time either.
 * The Web Locks API serialises them; a tab that waited then sends the rotated cookie.
 */
function withCrossTabLock<T>(task: () => Promise<T>): Promise<T> {
  if (typeof navigator !== 'undefined' && 'locks' in navigator) {
    return navigator.locks.request('linguaflow-token-refresh', task)
  }
  return task()
}
