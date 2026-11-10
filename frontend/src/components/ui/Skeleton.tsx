import { cn } from '@/lib/cn'

/**
 * Loading placeholder. Hidden from assistive tech: the surrounding region announces
 * the loading state (see PageSkeleton and SkeletonText).
 */
export function Skeleton({ className }: { className?: string }) {
  return (
    <div
      aria-hidden="true"
      className={cn('animate-pulse rounded-md bg-surface-muted', className)}
    />
  )
}

/** Several text-line skeletons with a screen-reader-only "Loading" announcement. */
export function SkeletonText({
  lines = 3,
  label = 'Loading',
}: {
  lines?: number
  label?: string
}) {
  return (
    <div role="status" aria-busy="true" className="space-y-2">
      <span className="sr-only">{label}</span>
      {Array.from({ length: lines }, (_, i) => (
        <Skeleton
          key={i}
          className={cn('h-4', i === lines - 1 ? 'w-2/3' : 'w-full')}
        />
      ))}
    </div>
  )
}
