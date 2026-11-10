import { createContext, useContext } from 'react'
import type { LoginRequest } from '@/api/types'
import type { CurrentUser } from './authApi'

/**
 * `loading` while a stored session is being restored on startup; then either
 * `authenticated` (an access token is held) or `anonymous`.
 */
export type AuthStatus = 'loading' | 'authenticated' | 'anonymous'

export interface AuthContextValue {
  status: AuthStatus
  /** The logged-in user's profile; undefined until it has loaded. */
  user: CurrentUser | undefined
  login: (credentials: LoginRequest) => Promise<void>
  logout: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export const CURRENT_USER_QUERY_KEY = ['auth', 'current-user'] as const

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside <AuthProvider>')
  return context
}
