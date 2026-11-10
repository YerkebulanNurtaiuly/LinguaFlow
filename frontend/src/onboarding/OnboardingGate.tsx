import { Navigate, Outlet, useLocation } from 'react-router'
import type { AuthPageState } from '@/auth/redirect'
import { FullPageLoader } from '@/components/layout/FullPageLoader'
import { Alert, Button } from '@/components/ui'
import { useMyLanguages } from '@/languages/queries'

/**
 * Inside ProtectedRoute, around the app: a user who doesn't learn any language yet is
 * sent to /onboarding first (with the page they wanted in `state.from`).
 */
export function OnboardingGate() {
  const languages = useMyLanguages()
  const location = useLocation()

  if (languages.isPending) return <FullPageLoader />

  if (languages.isError) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-canvas px-4">
        <div className="w-full max-w-sm space-y-4">
          <Alert variant="danger" title="Couldn't load your profile">
            Check your connection and try again.
          </Alert>
          <Button
            variant="secondary"
            className="w-full"
            loading={languages.isFetching}
            onClick={() => void languages.refetch()}
          >
            Try again
          </Button>
        </div>
      </div>
    )
  }

  if (languages.data.length === 0) {
    const state: AuthPageState = {
      from: `${location.pathname}${location.search}${location.hash}`,
    }
    return <Navigate to="/onboarding" replace state={state} />
  }

  return <Outlet />
}
