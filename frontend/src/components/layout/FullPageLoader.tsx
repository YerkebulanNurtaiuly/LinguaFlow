import { Spinner } from '@/components/ui'

/** Shown while the session is being restored, before any page can render. */
export function FullPageLoader() {
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
