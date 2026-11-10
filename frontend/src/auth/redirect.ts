/** Router state passed to /login and /register. */
export interface AuthPageState {
  /** The page the user wanted before being sent to log in */
  from?: string
  /** Set after a registration whose automatic login failed; prefills the email */
  registeredEmail?: string
}

export const DEFAULT_AFTER_LOGIN = '/dashboard'

/**
 * Where to go once logged in: the remembered page if it is a safe in-app path
 * (not another site, and not a login or registration page), else the dashboard.
 */
export function redirectTarget(state: unknown): string {
  const from =
    typeof state === 'object' && state !== null && 'from' in state
      ? state.from
      : undefined
  if (
    typeof from !== 'string' ||
    !from.startsWith('/') ||
    from.startsWith('//') ||
    from.includes('\\') ||
    /^\/(login|register)(?:[/?#]|$)/.test(from)
  ) {
    return DEFAULT_AFTER_LOGIN
  }
  return from
}

export function readAuthPageState(state: unknown): AuthPageState {
  if (typeof state !== 'object' || state === null) return {}
  const { from, registeredEmail } = state as Record<string, unknown>
  return {
    from: typeof from === 'string' ? from : undefined,
    registeredEmail:
      typeof registeredEmail === 'string' ? registeredEmail : undefined,
  }
}
