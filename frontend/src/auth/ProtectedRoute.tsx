import { Navigate, Outlet, useLocation } from 'react-router'
import { FullPageLoader } from '@/components/layout/FullPageLoader'
import { useAuth } from './authContext'
import type { AuthPageState } from './redirect'

/**
 * Renders its child routes only for a logged-in user. Anonymous visitors are sent to
 * /login, which receives the page they wanted in `state.from`.
 */
export function ProtectedRoute() {
  const { status } = useAuth()
  const location = useLocation()

  if (status === 'loading') return <FullPageLoader />

  if (status === 'anonymous') {
    const state: AuthPageState = {
      from: `${location.pathname}${location.search}${location.hash}`,
    }
    return <Navigate to="/login" replace state={state} />
  }

  return <Outlet />
}
