import type {
  HTMLAttributes,
  TableHTMLAttributes,
  TdHTMLAttributes,
  ThHTMLAttributes,
} from 'react'
import { cn } from '@/lib/cn'

/** Semantic table that scrolls horizontally on narrow screens. */
export function Table({
  className,
  ...props
}: TableHTMLAttributes<HTMLTableElement>) {
  return (
    <div className="overflow-x-auto rounded-xl border border-border bg-surface">
      <table className={cn('w-full text-left text-sm', className)} {...props} />
    </div>
  )
}

export function TableCaption(props: HTMLAttributes<HTMLTableCaptionElement>) {
  return <caption className="sr-only" {...props} />
}

export function THead({
  className,
  ...props
}: HTMLAttributes<HTMLTableSectionElement>) {
  return (
    <thead
      className={cn('bg-surface-muted text-fg-muted', className)}
      {...props}
    />
  )
}

export function TBody({
  className,
  ...props
}: HTMLAttributes<HTMLTableSectionElement>) {
  return (
    <tbody className={cn('divide-y divide-border', className)} {...props} />
  )
}

export function Tr(props: HTMLAttributes<HTMLTableRowElement>) {
  return <tr {...props} />
}

export function Th({
  className,
  scope = 'col',
  ...props
}: ThHTMLAttributes<HTMLTableCellElement>) {
  return (
    <th
      scope={scope}
      className={cn(
        'px-4 py-3 text-xs font-semibold uppercase tracking-wide',
        className,
      )}
      {...props}
    />
  )
}

export function Td({
  className,
  ...props
}: TdHTMLAttributes<HTMLTableCellElement>) {
  return <td className={cn('px-4 py-3 text-fg', className)} {...props} />
}
