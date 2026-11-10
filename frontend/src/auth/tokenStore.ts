type Listener = (token: string | null) => void

let accessToken: string | null = null
const listeners = new Set<Listener>()

/**
 * Holds the current access token in memory only (never localStorage), so a script
 * injected into the page cannot read it from storage. After a reload the session is
 * restored from the HttpOnly refresh cookie instead.
 */
export const tokenStore = {
  get(): string | null {
    return accessToken
  },

  set(token: string | null): void {
    if (token === accessToken) return
    accessToken = token
    for (const listener of listeners) listener(token)
  },

  subscribe(listener: Listener): () => void {
    listeners.add(listener)
    return () => {
      listeners.delete(listener)
    }
  },
}
