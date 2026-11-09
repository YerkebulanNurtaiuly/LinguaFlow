import { cloneElement, useId, type ReactElement, type ReactNode } from 'react'

interface FieldControlProps {
  id?: string
  'aria-invalid'?: boolean
  'aria-describedby'?: string
  required?: boolean
}

export interface FieldProps {
  label: ReactNode
  /** Help text below the control */
  hint?: ReactNode
  /** Error message; marks the control aria-invalid */
  error?: ReactNode
  required?: boolean
  /** The control (Input, Select, Textarea); ids and ARIA wiring are added */
  children: ReactElement<FieldControlProps>
}

/**
 * Label + control + hint/error, with the accessibility wiring done once: the label
 * targets the control, hint and error are linked via aria-describedby, and an error sets
 * aria-invalid.
 */
export function Field({ label, hint, error, required, children }: FieldProps) {
  const generatedId = useId()
  const id = children.props.id ?? generatedId
  const hintId = hint ? `${id}-hint` : undefined
  const errorId = error ? `${id}-error` : undefined
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined

  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="block text-sm font-medium text-fg">
        {label}
        {required && (
          <span className="ml-0.5 text-danger" aria-hidden="true">
            *
          </span>
        )}
      </label>
      {cloneElement(children, {
        id,
        required: required ?? children.props.required,
        'aria-invalid': error ? true : undefined,
        'aria-describedby': describedBy,
      })}
      {hint && (
        <p id={hintId} className="text-xs text-fg-subtle">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className="text-xs font-medium text-danger">
          {error}
        </p>
      )}
    </div>
  )
}
