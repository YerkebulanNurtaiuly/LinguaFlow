import { CircleAlert, CircleCheck, Info } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'

export type AlertVariant = 'danger' | 'success' | 'info'

const styles: Record<AlertVariant, string> = {
  danger: 'border-danger/30 bg-danger-soft text-danger-soft-fg',
  success: 'border-border bg-success-soft text-success-soft-fg',
  info: 'border-border bg-info-soft text-info-soft-fg',
}

const icons = { danger: CircleAlert, success: CircleCheck, info: Info }

export interface AlertProps {
  variant?: AlertVariant
  title?: ReactNode
  children?: ReactNode
  className?: string
}

/**
 * Inline message, e.g. a form-level error above the fields. Danger alerts use
 * role="alert" so they are announced at once; others are polite status messages.
 */
export function Alert({
  variant = 'info',
  title,
  children,
  className,
}: AlertProps) {
  const Icon = icons[variant]
  return (
    <div
      role={variant === 'danger' ? 'alert' : 'status'}
      className={cn(
        'flex gap-3 rounded-md border px-3 py-2.5 text-sm',
        styles[variant],
        className,
      )}
    >
      <Icon className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
      <div className="min-w-0 space-y-0.5">
        {title && <p className="font-medium">{title}</p>}
        {children && <div>{children}</div>}
      </div>
    </div>
  )
}
