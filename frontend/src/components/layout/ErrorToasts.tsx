import { useEffect } from 'react'
import { useToast } from '@/components/ui'
import { errorNotifier } from '@/lib/errorNotifier'

/** Same notice again within this window is dropped (e.g. several queries failing at once). */
const DEDUPE_MS = 4_000

/** Shows the global query and mutation errors as toasts. Render once, inside ToastProvider. */
export function ErrorToasts() {
  const { toast } = useToast()

  useEffect(() => {
    const recent = new Map<string, number>()
    return errorNotifier.listen(({ title, description }) => {
      const key = `${title}\n${description}`
      const now = Date.now()
      if ((recent.get(key) ?? 0) > now - DEDUPE_MS) return
      recent.set(key, now)
      toast({ title, description, variant: 'error' })
    })
  }, [toast])

  return null
}
