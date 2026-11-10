export interface ErrorNotice {
  title: string
  description: string
}

type Listener = (notice: ErrorNotice) => void

let listener: Listener | null = null

/**
 * Passes errors from code outside React (the query client's caches) to the toast
 * system. `<ErrorToasts />` registers itself as the listener; without it, notices are
 * dropped.
 */
export const errorNotifier = {
  notify(notice: ErrorNotice): void {
    listener?.(notice)
  },

  listen(next: Listener): () => void {
    listener = next
    return () => {
      if (listener === next) listener = null
    }
  },
}
