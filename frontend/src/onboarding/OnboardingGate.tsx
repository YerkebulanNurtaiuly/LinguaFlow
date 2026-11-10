import { Navigate, Outlet, useLocation } from 'react-router'
import type { AuthPageState } from '@/auth/redirect'
import { FullPageLoader } from '@/components/layout/FullPageLoader'
import { describeError } from '@/api/errors'
import { LoadError } from '@/components/ui'
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
        <LoadError
          className="w-full max-w-sm"
          title="Couldn't load your profile"
          description={describeError(languages.error)}
          retrying={languages.isFetching}
          onRetry={() => void languages.refetch()}
        />
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
