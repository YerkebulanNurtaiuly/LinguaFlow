import { createContext, useContext } from 'react'

export type ToastVariant = 'success' | 'error' | 'info'

export interface ToastOptions {
  title: string
  description?: string
  variant?: ToastVariant
  /** Milliseconds before auto-dismiss; errors default to 8 s, others to 5 s */
  duration?: number
}

export interface ToastApi {
  toast: (options: ToastOptions) => void
  dismiss: (id: number) => void
}

export const ToastContext = createContext<ToastApi | null>(null)

/** `const { toast } = useToast(); toast({ title: 'Saved', variant: 'success' })` */
export function useToast(): ToastApi {
  const api = useContext(ToastContext)
  if (!api) throw new Error('useToast must be used inside <ToastProvider>')
  return api
}
