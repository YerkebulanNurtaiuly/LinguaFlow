import { Navigate, Outlet, useLocation } from 'react-router'
import { FullPageLoader } from '@/components/layout/FullPageLoader'
import { useAuth } from './authContext'
import { redirectTarget } from './redirect'

/**
 * For /login and /register: a logged-in user is sent on to the page they wanted
 * (`state.from`) or the dashboard. This is also what moves the user on right after
 * logging in or registering, so the pages themselves never navigate on success.
 */
export function GuestRoute() {
  const { status } = useAuth()
  const location = useLocation()

  if (status === 'loading') return <FullPageLoader />
  if (status === 'authenticated') {
    return <Navigate to={redirectTarget(location.state)} replace />
  }
  return <Outlet />
}
