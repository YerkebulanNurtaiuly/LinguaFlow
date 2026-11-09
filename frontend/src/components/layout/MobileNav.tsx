import { X } from 'lucide-react'
import { useEffect, useRef } from 'react'
import { NavLinks } from './NavLinks'

interface MobileNavProps {
  open: boolean
  onClose: () => void
}

export const MOBILE_NAV_ID = 'mobile-navigation'

/** Slide-over navigation for small screens; closes on Escape, backdrop click or navigation. */
export function MobileNav({ open, onClose }: MobileNavProps) {
  const closeButton = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    closeButton.current?.focus()
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [open, onClose])

  if (!open) return null

  return (
    <div className="fixed inset-0 z-40 lg:hidden">
      <div
        className="absolute inset-0 bg-slate-900/40"
        aria-hidden="true"
        onClick={onClose}
        data-testid="mobile-nav-backdrop"
      />
      <div
        id={MOBILE_NAV_ID}
        role="dialog"
        aria-modal="true"
        aria-label="Navigation"
        className="absolute inset-y-0 left-0 flex w-72 max-w-[85vw] flex-col bg-white p-4 shadow-xl"
      >
        <div className="mb-4 flex justify-end">
          <button
            ref={closeButton}
            type="button"
            onClick={onClose}
            className="rounded-md p-2 text-slate-600 hover:bg-slate-100"
          >
            <X className="size-5" aria-hidden="true" />
            <span className="sr-only">Close navigation</span>
          </button>
        </div>
        <nav aria-label="Main">
          <NavLinks onNavigate={onClose} />
        </nav>
      </div>
    </div>
  )
}
