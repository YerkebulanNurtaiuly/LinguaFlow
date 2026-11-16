/**
 * Browser error reporting (OPS-04). Crashes and uncaught errors are posted to the
 * backend's `/api/client-errors`, which logs them for the developers and forwards them to
 * Sentry where that's configured. The browser itself never talks to a third party, so
 * the Content-Security-Policy stays `'self'`.
 *
 * - Only in production builds: in development the console shows everything anyway.
 * - At most `MAX_REPORTS` per page load, each distinct message once, so a render loop
 *   can't flood the server (which also rate-limits per IP).
 * - No personal data: the path is sent without its query or fragment, and no user,
 *   token or form content.
 * - Reporting never throws: a failed report is dropped.
 */

export type ErrorKind = 'render' | 'error' | 'unhandledrejection'

export const MAX_REPORTS = 10

let sent = 0
const seen = new Set<string>()
let release: Promise<string | null> | null = null

function describe(error: unknown): { message: string; stack?: string } {
  if (error instanceof Error) {
    return { message: `${error.name}: ${error.message}`, stack: error.stack }
  }
  if (typeof error === 'string') return { message: error }
  try {
    return { message: JSON.stringify(error) }
  } catch {
    return { message: Object.prototype.toString.call(error) }
  }
}

/** The deployed version (version.txt), read once; null when it can't be read. */
function deployedRelease(): Promise<string | null> {
  release ??= fetch('/version.txt', { cache: 'no-store' })
    .then((response) => (response.ok ? response.text() : null))
    .then((text) => text?.trim().slice(0, 64) ?? null)
    .catch(() => null)
  return release
}

/** Sends one report, unless it's a repeat or the page has sent enough. */
export async function reportError(kind: ErrorKind, error: unknown) {
  const { message, stack } = describe(error)
  const key = `${kind}|${message}`
  if (sent >= MAX_REPORTS || seen.has(key)) return
  seen.add(key)
  sent += 1
  try {
    await fetch('/api/client-errors', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      keepalive: true,
      body: JSON.stringify({
        kind,
        message: message.slice(0, 500),
        stack: stack?.slice(0, 4000),
        path: window.location.pathname.slice(0, 300),
        release: await deployedRelease(),
      }),
    })
  } catch {
    // Reporting is best effort
  }
}

/** Reports uncaught errors and rejections from now on (production only). */
export function installErrorReporting() {
  window.addEventListener('error', (event) => {
    void reportError('error', event.error ?? event.message)
  })
  window.addEventListener('unhandledrejection', (event) => {
    void reportError('unhandledrejection', event.reason)
  })
}

/** For tests: forget what was sent. */
export function resetErrorReporting() {
  sent = 0
  seen.clear()
  release = null
}
