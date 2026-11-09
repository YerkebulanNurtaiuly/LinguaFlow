import type { Ref, SelectHTMLAttributes } from 'react'
import { cn } from '@/lib/cn'
import { controlClasses } from './controlStyles'

export interface SelectOption {
  value: string
  label: string
  disabled?: boolean
}

export interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {
  options: SelectOption[]
  /** Optional first, empty option (e.g. "Choose a level…") */
  placeholder?: string
  ref?: Ref<HTMLSelectElement>
}

/** Native select: keyboard and screen-reader support come from the browser. */
export function Select({
  options,
  placeholder,
  className,
  ref,
  ...props
}: SelectProps) {
  return (
    <select
      ref={ref}
      className={cn(controlClasses, 'h-10', className)}
      {...props}
    >
      {placeholder !== undefined && <option value="">{placeholder}</option>}
      {options.map((option) => (
        <option
          key={option.value}
          value={option.value}
          disabled={option.disabled}
        >
          {option.label}
        </option>
      ))}
    </select>
  )
}
