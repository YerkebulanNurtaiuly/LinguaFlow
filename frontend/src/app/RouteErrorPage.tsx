import { TriangleAlert } from 'lucide-react'
import { useEffect } from 'react'
import {
  isRouteErrorResponse,
  Link,
  useLocation,
  useNavigate,
  useRouteError,
} from 'react-router'
import { Button } from '@/components/ui'
import { cn } from '@/lib/cn'

function technicalDetails(error: unknown): string | undefined {
  if (error instanceof Error) return error.stack ?? error.message
  if (isRouteErrorResponse(error)) return `${error.status} ${error.statusText}`
  return error === undefined ? undefined : String(error)
}

/**
 * Router `errorElement`: shown when rendering a route throws. `inline` is used inside
 * the app shell, so a crashing section keeps the navigation and the user can move on;
 * the full-page variant catches everything else.
 */
export function RouteErrorPage({ inline = false }: { inline?: boolean }) {
  const error = useRouteError()
  const navigate = useNavigate()
  const location = useLocation()

  useEffect(() => {
    document.title = 'Something went wrong · LinguaFlow'
  }, [])

  const details = import.meta.env.DEV ? technicalDetails(error) : undefined

  return (
    <div
      className={cn(
        'flex flex-col gap-4',
        inline
          ? 'max-w-2xl'
          : 'mx-auto min-h-screen max-w-2xl justify-center bg-canvas px-4',
      )}
    >
      <TriangleAlert className="size-8 text-danger" aria-hidden="true" />
      <h1 className="text-2xl font-semibold tracking-tight text-fg">
        Something went wrong
      </h1>
      <p className="text-fg-muted">
        An unexpected error stopped this page from loading. Try again; if it
        keeps happening, reload LinguaFlow.
      </p>
      <div className="flex flex-wrap gap-2">
        <Button
          onClick={() =>
            // Navigating to the same location clears the router's error state
            navigate(`${location.pathname}${location.search}${location.hash}`, {
              replace: true,
            })
          }
        >
          Try again
        </Button>
        <Button variant="secondary" onClick={() => window.location.reload()}>
          Reload
        </Button>
        {!inline && (
          <Link
            to="/dashboard"
            className="inline-flex h-10 items-center px-2 text-sm font-medium text-primary hover:underline"
          >
            Go to the dashboard
          </Link>
        )}
      </div>
      {details && (
        <details className="rounded-md border border-border bg-surface-muted p-3 text-xs text-fg-muted">
          <summary className="cursor-pointer font-medium">
            Technical details (development only)
          </summary>
          <pre className="mt-2 overflow-x-auto whitespace-pre-wrap">
            {details}
          </pre>
        </details>
      )}
    </div>
  )
}
