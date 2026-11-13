import type { ReactNode } from 'react'

/**
 * A tooltip floating at (x, y) inside a relative container, kept within its width.
 * Values lead and labels follow (dataviz interaction rules); the content is React, so
 * learner data is never parsed as HTML.
 */
export function ChartTooltip({
  x,
  y,
  width,
  children,
}: {
  x: number
  y: number
  width: number
  children: ReactNode
}) {
  const tooltipWidth = 220
  const left = Math.min(Math.max(x - tooltipWidth / 2, 0), width - tooltipWidth)
  return (
    <div
      role="tooltip"
      className="pointer-events-none absolute z-10 rounded-lg border border-border bg-surface px-3 py-2 text-sm shadow-md"
      style={{ left, top: y, width: tooltipWidth }}
    >
      {children}
    </div>
  )
}

/** A short stroke in a series colour: the key for a line in legends and tooltips. */
export function LineKey({ className }: { className: string }) {
  return (
    <svg width="16" height="8" aria-hidden="true" className="shrink-0">
      <line
        x1="1"
        y1="4"
        x2="15"
        y2="4"
        strokeWidth="2"
        strokeLinecap="round"
        className={className}
      />
    </svg>
  )
}

/** A small filled square in a fill colour: the key for blocks and cells. */
export function Swatch({ className }: { className: string }) {
  return (
    <span
      aria-hidden="true"
      className={`inline-block size-3 shrink-0 rounded-sm ${className}`}
    />
  )
}
