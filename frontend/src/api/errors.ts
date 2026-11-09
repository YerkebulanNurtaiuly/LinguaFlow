import { isAxiosError } from 'axios'
import type { ApiErrorCode, ApiProblem } from './types'

function isApiProblem(value: unknown): value is ApiProblem {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as Record<string, unknown>
  return (
    typeof candidate.code === 'string' && typeof candidate.status === 'number'
  )
}

/** The backend's problem details for a failed request, if the error carries one. */
export function getApiProblem(error: unknown): ApiProblem | undefined {
  if (!isAxiosError(error)) return undefined
  const data: unknown = error.response?.data
  return isApiProblem(data) ? data : undefined
}

export function hasErrorCode(error: unknown, code: ApiErrorCode): boolean {
  return getApiProblem(error)?.code === code
}

/** Validation messages keyed by field name, for showing next to form inputs. */
export function getFieldErrors(error: unknown): Record<string, string> {
  const fieldErrors: Record<string, string> = {}
  for (const { field, message } of getApiProblem(error)?.errors ?? []) {
    if (field && message && !(field in fieldErrors)) {
      fieldErrors[field] = message
    }
  }
  return fieldErrors
}

/** A message that is safe to show to the user. */
export function getErrorMessage(
  error: unknown,
  fallback = 'Something went wrong. Please try again.',
): string {
  return getApiProblem(error)?.detail ?? fallback
}
