import type { Ref, TextareaHTMLAttributes } from 'react'
import { cn } from '@/lib/cn'
import { controlClasses } from './controlStyles'

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  ref?: Ref<HTMLTextAreaElement>
}

export function Textarea({
  className,
  rows = 4,
  ref,
  ...props
}: TextareaProps) {
  return (
    <textarea
      ref={ref}
      rows={rows}
      className={cn(controlClasses, 'py-2', className)}
      {...props}
    />
  )
}
