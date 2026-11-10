import { Navigate, Outlet, useLocation } from 'react-router'
import { Spinner } from '@/components/ui'
import { useAuth } from './authContext'

/** Where the login page should send the user back to after logging in. */
export interface LoginRedirectState {
  from?: string
}

/**
 * Renders its child routes only for a logged-in user. Anonymous visitors are sent to
 * /login, which receives the page they wanted in `state.from`.
 */
export function ProtectedRoute() {
  const { status } = useAuth()
  const location = useLocation()

  if (status === 'loading') {
    return (
      <div
        role="status"
        className="flex min-h-screen items-center justify-center gap-3 bg-canvas text-fg-muted"
      >
        <Spinner className="size-5" />
        <span className="text-sm">Loading…</span>
      </div>
    )
  }

  if (status === 'anonymous') {
    const state: LoginRedirectState = {
      from: `${location.pathname}${location.search}${location.hash}`,
    }
    return <Navigate to="/login" replace state={state} />
  }

  return <Outlet />
}
