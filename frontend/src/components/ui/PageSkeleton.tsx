import { cn } from '@/lib/cn'
import { Skeleton } from './Skeleton'

/** Placeholder for one card while its content loads (no announcement of its own). */
export function CardSkeleton({ lines = 3 }: { lines?: number }) {
  return (
    <div className="rounded-xl border border-border bg-surface p-5 shadow-sm">
      <Skeleton className="mb-4 h-5 w-1/3" />
      <div className="space-y-2">
        {Array.from({ length: lines }, (_, i) => (
          <Skeleton
            key={i}
            className={cn('h-4', i === lines - 1 ? 'w-2/3' : 'w-full')}
          />
        ))}
      </div>
    </div>
  )
}

export interface PageSkeletonProps {
  /** Announced to screen readers, e.g. "Loading settings" */
  label?: string
  /** Show the page title and intro placeholders (off when the real heading is shown) */
  withHeader?: boolean
  cards?: number
  className?: string
}

/**
 * The standard loading state for a page or section: optional title placeholders and a
 * few card skeletons. It is a busy status region, announced once by screen readers.
 */
export function PageSkeleton({
  label = 'Loading',
  withHeader = false,
  cards = 2,
  className,
}: PageSkeletonProps) {
  return (
    <div
      role="status"
      aria-busy="true"
      aria-label={label}
      className={cn('space-y-6', className)}
    >
      {withHeader && (
        <div className="space-y-3">
          <Skeleton className="h-7 w-48" />
          <Skeleton className="h-4 w-80 max-w-full" />
        </div>
      )}
      {Array.from({ length: cards }, (_, i) => (
        <CardSkeleton key={i} lines={i === 0 ? 3 : 5} />
      ))}
    </div>
  )
}
