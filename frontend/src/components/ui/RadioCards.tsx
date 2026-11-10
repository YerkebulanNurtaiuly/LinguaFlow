import { type ReactNode, useId } from 'react'
import { cn } from '@/lib/cn'

export interface RadioCardOption<T extends string> {
  value: T
  label: ReactNode
  description?: ReactNode
  /** Small extra on the right, e.g. a badge */
  aside?: ReactNode
}

export interface RadioCardsProps<T extends string> {
  /** Group label (rendered as the fieldset legend) */
  legend: ReactNode
  /** Hide the legend visually but keep it for screen readers */
  hideLegend?: boolean
  options: readonly RadioCardOption<T>[]
  value: T | undefined
  onChange: (value: T) => void
  columns?: 1 | 2
  className?: string
}

/**
 * A single choice shown as selectable cards. Built on native radio inputs, so arrow
 * keys move the selection and screen readers announce "1 of 4, selected".
 */
export function RadioCards<T extends string>({
  legend,
  hideLegend = false,
  options,
  value,
  onChange,
  columns = 1,
  className,
}: RadioCardsProps<T>) {
  const name = useId()

  return (
    <fieldset className={className}>
      <legend
        className={cn(
          'mb-3 text-sm font-medium text-fg',
          hideLegend && 'sr-only',
        )}
      >
        {legend}
      </legend>
      <div className={cn('grid gap-2', columns === 2 && 'sm:grid-cols-2')}>
        {options.map((option) => {
          const descriptionId = option.description
            ? `${name}-${option.value}-description`
            : undefined
          return (
            <label
              key={option.value}
              className={cn(
                'flex cursor-pointer items-start gap-3 rounded-lg border border-border bg-surface p-3 transition-colors hover:bg-surface-muted',
                'has-checked:border-primary has-checked:bg-primary-soft',
                'has-focus-visible:ring-2 has-focus-visible:ring-ring',
              )}
            >
              <input
                type="radio"
                name={name}
                value={option.value}
                checked={value === option.value}
                onChange={() => onChange(option.value)}
                aria-describedby={descriptionId}
                className="mt-0.5 size-4 shrink-0 accent-primary focus:outline-none"
              />
              <span className="min-w-0 flex-1">
                <span className="block text-sm font-medium text-fg">
                  {option.label}
                </span>
                {option.description && (
                  <span
                    id={descriptionId}
                    className="mt-0.5 block text-sm text-fg-muted"
                  >
                    {option.description}
                  </span>
                )}
              </span>
              {option.aside}
            </label>
          )
        })}
      </div>
    </fieldset>
  )
}
