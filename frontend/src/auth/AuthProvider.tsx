import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  type ReactNode,
  useCallback,
  useEffect,
  useMemo,
  useState,
  useSyncExternalStore,
} from 'react'
import type { LoginRequest } from '@/api/types'
import { refreshAccessToken } from '@/lib/api'
import * as authApi from './authApi'
import {
  AuthContext,
  type AuthContextValue,
  type AuthStatus,
  CURRENT_USER_QUERY_KEY,
} from './authContext'
import { tokenStore } from './tokenStore'

// Stable functions, so useSyncExternalStore subscribes once
const subscribeToToken = (listener: () => void) =>
  tokenStore.subscribe(listener)
const readToken = () => tokenStore.get()

/**
 * Owns the session: restores it from the refresh cookie on startup, exposes login and
 * logout, and loads the current user. Whenever the session ends (logout, or a refresh
 * the server rejects) all cached query data is dropped, so the next user never sees it.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const token = useSyncExternalStore(subscribeToToken, readToken)
  const [restoring, setRestoring] = useState(() => tokenStore.get() === null)

  useEffect(() => {
    if (!restoring) return
    let active = true
    // Shared with any parallel caller (e.g. StrictMode's second effect run)
    refreshAccessToken()
      .catch(() => null)
      .finally(() => {
        if (active) setRestoring(false)
      })
    return () => {
      active = false
    }
  }, [restoring])

  useEffect(
    () =>
      tokenStore.subscribe((next) => {
        if (next === null) queryClient.clear()
      }),
    [queryClient],
  )

  const status: AuthStatus = token
    ? 'authenticated'
    : restoring
      ? 'loading'
      : 'anonymous'

  const { data: user } = useQuery({
    queryKey: CURRENT_USER_QUERY_KEY,
    queryFn: authApi.fetchCurrentUser,
    enabled: status === 'authenticated',
    // Changes only through this app's own profile mutations, which update the cache
    staleTime: Infinity,
  })

  const login = useCallback(async (credentials: LoginRequest) => {
    await authApi.login(credentials)
  }, [])

  const logout = useCallback(async () => {
    await authApi.logout().catch(() => undefined)
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      user: status === 'authenticated' ? user : undefined,
      login,
      logout,
    }),
    [status, user, login, logout],
  )

  return <AuthContext value={value}>{children}</AuthContext>
}
