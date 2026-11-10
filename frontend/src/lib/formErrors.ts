import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'
import { getFieldErrors } from '@/api/errors'

/**
 * Shows a backend VALIDATION_FAILED response next to the matching form fields.
 * Returns true if at least one field error was applied; errors for fields the form
 * doesn't have are ignored, so the caller should then show a form-level message.
 *
 * Backend messages are fragments ("is incorrect"); pass `labels` to turn them into
 * sentences ("Current password is incorrect.").
 */
export function applyServerFieldErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  fields: readonly Path<T>[],
  labels: Partial<Record<Path<T>, string>> = {},
): boolean {
  const serverErrors = getFieldErrors(error)
  let applied = false
  for (const field of fields) {
    const fragment = serverErrors[field]
    if (fragment) {
      const message = toSentence(labels[field], fragment)
      setError(field, { type: 'server', message }, { shouldFocus: !applied })
      applied = true
    }
  }
  return applied
}

function toSentence(label: string | undefined, fragment: string): string {
  if (!label || !/^[a-z]/.test(fragment)) return fragment
  return `${label} ${fragment.replace(/\.$/, '')}.`
}
