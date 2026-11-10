import type { ReactNode } from 'react'
import { Alert } from './Alert'
import { Button } from './Button'

export interface LoadErrorProps {
  /** What failed, e.g. "Couldn't load your settings" */
  title: string
  /** Defaults to a hint to check the connection */
  description?: ReactNode
  onRetry?: () => void
  /** Shows a spinner on the retry button */
  retrying?: boolean
  className?: string
}

/**
 * The standard "this didn't load" state: an alert plus a Try again button. Use it where
 * a query's first load failed; failures of later background refreshes become toasts.
 */
export function LoadError({
  title,
  description = 'Check your connection and try again.',
  onRetry,
  retrying = false,
  className,
}: LoadErrorProps) {
  return (
    <div className={className}>
      <div className="space-y-3">
        <Alert variant="danger" title={title}>
          {description}
        </Alert>
        {onRetry && (
          <Button variant="secondary" loading={retrying} onClick={onRetry}>
            Try again
          </Button>
        )}
      </div>
    </div>
  )
}
