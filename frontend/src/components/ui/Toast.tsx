import { CheckCircle2, Info, TriangleAlert, X } from 'lucide-react'
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { cn } from '@/lib/cn'
import { ToastContext, type ToastOptions } from './toastContext'

interface ToastItem extends Required<
  Pick<ToastOptions, 'title' | 'variant' | 'duration'>
> {
  id: number
  description?: string
}

const icons = { success: CheckCircle2, error: TriangleAlert, info: Info }
const accents = {
  success: 'text-success-soft-fg',
  error: 'text-danger',
  info: 'text-info-soft-fg',
}

/**
 * Renders toasts in two live regions: errors are announced immediately (assertive),
 * everything else politely. Toasts dismiss themselves and can be closed manually.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([])
  const nextId = useRef(1)

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((t) => t.id !== id))
  }, [])

  const toast = useCallback((options: ToastOptions) => {
    const variant = options.variant ?? 'info'
    const item: ToastItem = {
      id: nextId.current++,
      title: options.title,
      description: options.description,
      variant,
      duration: options.duration ?? (variant === 'error' ? 8000 : 5000),
    }
    setToasts((current) => [...current.slice(-4), item])
  }, [])

  const api = useMemo(() => ({ toast, dismiss }), [toast, dismiss])
  const polite = toasts.filter((t) => t.variant !== 'error')
  const assertive = toasts.filter((t) => t.variant === 'error')

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="pointer-events-none fixed inset-x-0 bottom-0 z-[60] flex flex-col items-center gap-2 p-4 sm:items-end">
        <div
          role="status"
          aria-live="polite"
          className="flex w-full flex-col items-center gap-2 sm:items-end"
        >
          {polite.map((t) => (
            <ToastCard key={t.id} item={t} onDismiss={dismiss} />
          ))}
        </div>
        <div
          role="alert"
          aria-live="assertive"
          className="flex w-full flex-col items-center gap-2 sm:items-end"
        >
          {assertive.map((t) => (
            <ToastCard key={t.id} item={t} onDismiss={dismiss} />
          ))}
        </div>
      </div>
    </ToastContext.Provider>
  )
}

function ToastCard({
  item,
  onDismiss,
}: {
  item: ToastItem
  onDismiss: (id: number) => void
}) {
  useEffect(() => {
    const timer = setTimeout(() => onDismiss(item.id), item.duration)
    return () => clearTimeout(timer)
  }, [item.id, item.duration, onDismiss])

  const Icon = icons[item.variant]
  return (
    <div className="pointer-events-auto flex w-full max-w-sm items-start gap-3 rounded-lg border border-border bg-surface p-4 shadow-lg">
      <Icon
        className={cn('mt-0.5 size-5 shrink-0', accents[item.variant])}
        aria-hidden="true"
      />
      <div className="flex-1">
        <p className="text-sm font-medium text-fg">{item.title}</p>
        {item.description && (
          <p className="mt-0.5 text-sm text-fg-muted">{item.description}</p>
        )}
      </div>
      <button
        type="button"
        onClick={() => onDismiss(item.id)}
        className="-m-1 rounded-md p-1 text-fg-subtle hover:bg-surface-muted hover:text-fg"
      >
        <X className="size-4" aria-hidden="true" />
        <span className="sr-only">Dismiss notification</span>
      </button>
    </div>
  )
}
