import type { HTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

export type BadgeVariant =
  'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info'

const variants: Record<BadgeVariant, string> = {
  neutral: 'bg-surface-muted text-fg-muted',
  primary: 'bg-primary-soft text-primary-soft-fg',
  success: 'bg-success-soft text-success-soft-fg',
  warning: 'bg-warning-soft text-warning-soft-fg',
  danger: 'bg-danger-soft text-danger-soft-fg',
  info: 'bg-info-soft text-info-soft-fg',
}

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  variant?: BadgeVariant
}

export function Badge({
  variant = 'neutral',
  className,
  ...props
}: BadgeProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium',
        variants[variant],
        className,
      )}
      {...props}
    />
  )
}

export type CefrLevel = 'A1' | 'A2' | 'B1' | 'B2' | 'C1' | 'C2'

const cefrVariants: Record<CefrLevel, BadgeVariant> = {
  A1: 'success',
  A2: 'success',
  B1: 'info',
  B2: 'info',
  C1: 'primary',
  C2: 'primary',
}

/** CEFR level chip; the level text itself carries the meaning (color is decoration). */
export function CefrBadge({ level }: { level: CefrLevel }) {
  return (
    <Badge variant={cefrVariants[level]} title={`CEFR level ${level}`}>
      {level}
    </Badge>
  )
}

export type KnowledgeStatus = 'NEW' | 'LEARNING' | 'LEARNED' | 'MASTERED'

const statusLabels: Record<KnowledgeStatus, string> = {
  NEW: 'New',
  LEARNING: 'Learning',
  LEARNED: 'Learned',
  MASTERED: 'Mastered',
}

const statusVariants: Record<KnowledgeStatus, BadgeVariant> = {
  NEW: 'neutral',
  LEARNING: 'warning',
  LEARNED: 'info',
  MASTERED: 'success',
}

export function StatusBadge({ status }: { status: KnowledgeStatus }) {
  return <Badge variant={statusVariants[status]}>{statusLabels[status]}</Badge>
}
