import { Outlet } from 'react-router'
import { RouteTitle } from '@/app/RouteTitle'
import { Logo } from '@/components/layout/Logo'

/** Centered card layout for login and registration (no app navigation). */
export function AuthLayout() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-canvas px-4 py-10">
      <RouteTitle />
      <div className="mb-8">
        <Logo />
      </div>
      <main className="w-full max-w-sm rounded-xl border border-border bg-surface p-6 shadow-sm">
        <Outlet />
      </main>
    </div>
  )
}
