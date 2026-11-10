import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'
import { getFieldErrors } from '@/api/errors'

/**
 * Shows a backend VALIDATION_FAILED response next to the matching form fields.
 * Returns true if at least one field error was applied; errors for fields the form
 * doesn't have are ignored, so the caller should then show a form-level message.
 */
export function applyServerFieldErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  fields: readonly Path<T>[],
): boolean {
  const serverErrors = getFieldErrors(error)
  let applied = false
  for (const field of fields) {
    const message = serverErrors[field]
    if (message) {
      setError(field, { type: 'server', message }, { shouldFocus: !applied })
      applied = true
    }
  }
  return applied
}
