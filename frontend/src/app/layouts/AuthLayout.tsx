import { Outlet } from 'react-router'
import { RouteTitle } from '@/app/RouteTitle'
import { Logo } from '@/components/layout/Logo'
import { cn } from '@/lib/cn'

/**
 * Centered card layout without app navigation: login and registration (`narrow`),
 * and the onboarding wizard (`wide`).
 */
export function AuthLayout({
  width = 'narrow',
}: {
  width?: 'narrow' | 'wide'
}) {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-canvas px-4 py-10">
      <RouteTitle />
      <div className="mb-8">
        <Logo />
      </div>
      <main
        className={cn(
          'w-full rounded-xl border border-border bg-surface p-6 shadow-sm',
          width === 'narrow' ? 'max-w-sm' : 'max-w-xl sm:p-8',
        )}
      >
        <Outlet />
      </main>
    </div>
  )
}
