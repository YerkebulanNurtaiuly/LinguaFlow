import type { InputHTMLAttributes, Ref } from 'react'
import { cn } from '@/lib/cn'
import { controlClasses } from './controlStyles'

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  ref?: Ref<HTMLInputElement>
}

/** Text input; wrap in <Field> for label, hint and error. */
export function Input({ className, ref, ...props }: InputProps) {
  return (
    <input
      ref={ref}
      className={cn(controlClasses, 'h-10', className)}
      {...props}
    />
  )
}
