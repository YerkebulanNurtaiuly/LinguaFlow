import { Menu } from 'lucide-react'
import { useCallback, useState } from 'react'
import { Outlet } from 'react-router'
import { RouteTitle } from '@/app/RouteTitle'
import { BackendStatus } from '@/components/layout/BackendStatus'
import { Logo } from '@/components/layout/Logo'
import { MOBILE_NAV_ID, MobileNav } from '@/components/layout/MobileNav'
import { NavLinks } from '@/components/layout/NavLinks'

/**
 * Shell for the logged-in part of the app: sidebar on large screens, top bar with a
 * slide-over menu on small ones. Route protection (redirect to /login) arrives in FE-03.
 */
export function AppLayout() {
  const [mobileNavOpen, setMobileNavOpen] = useState(false)
  const closeMobileNav = useCallback(() => setMobileNavOpen(false), [])

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <RouteTitle />

      <aside className="fixed inset-y-0 left-0 hidden w-64 flex-col border-r border-slate-200 bg-white px-4 py-5 lg:flex">
        <div className="mb-6 px-3">
          <Logo />
        </div>
        <nav aria-label="Main" className="flex-1">
          <NavLinks />
        </nav>
        <div className="px-3">
          <BackendStatus />
        </div>
      </aside>

      <header className="sticky top-0 z-30 flex h-14 items-center gap-3 border-b border-slate-200 bg-white px-4 lg:hidden">
        <button
          type="button"
          onClick={() => setMobileNavOpen(true)}
          aria-expanded={mobileNavOpen}
          aria-controls={MOBILE_NAV_ID}
          className="rounded-md p-2 text-slate-600 hover:bg-slate-100"
        >
          <Menu className="size-5" aria-hidden="true" />
          <span className="sr-only">Open navigation</span>
        </button>
        <Logo />
      </header>
      <MobileNav open={mobileNavOpen} onClose={closeMobileNav} />

      <main className="px-4 py-6 sm:px-6 lg:ml-64 lg:px-10 lg:py-10">
        <Outlet />
      </main>
    </div>
  )
}
