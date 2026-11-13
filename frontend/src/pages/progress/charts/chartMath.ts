/** 0.875 → "88%". */
export function percent(value: number): string {
  return `${Math.round(value * 100)}%`
}

/** "2026-11-09" → "9 Nov", on the calendar date itself (no time zone shift). */
export function shortDate(iso: string): string {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString(undefined, {
    day: 'numeric',
    month: 'short',
    timeZone: 'UTC',
  })
}

/**
 * SVG paths for a line with gaps: one path per run of consecutive values, so a week
 * without answers breaks the line instead of drawing through it. A run of one point
 * draws no path (the chart marks it with a dot).
 */
export function linePaths(
  values: (number | undefined)[],
  x: (index: number) => number,
  y: (value: number) => number,
): string[] {
  const paths: string[] = []
  let current: string[] = []
  const flush = () => {
    if (current.length > 1) paths.push(current.join(' '))
    current = []
  }
  values.forEach((value, index) => {
    if (value === undefined) {
      flush()
      return
    }
    current.push(`${current.length === 0 ? 'M' : 'L'}${x(index)},${y(value)}`)
  })
  flush()
  return paths
}

/** 0 for no practice, else 1–4 by quarter of the busiest day. */
export function activityLevel(answers: number, max: number): number {
  if (answers <= 0 || max <= 0) return 0
  return Math.min(4, Math.ceil((answers / max) * 4))
}

/** Each part's share of the whole, 0–1; a whole of 0 gives zeros. */
export function shares(counts: number[]): number[] {
  const total = counts.reduce((sum, count) => sum + count, 0)
  return counts.map((count) => (total === 0 ? 0 : count / total))
}

/**
 * Where each block of a segmented bar goes, in px: widths proportional to the shares,
 * with a {@code gap} of surface between visible blocks; a zero share gets width 0.
 */
export function segmentLayout(
  parts: number[],
  width: number,
  gap = 2,
): { left: number; width: number }[] {
  const visible = parts.filter((share) => share > 0).length
  const usable = width - gap * Math.max(0, visible - 1)
  const layout: { left: number; width: number }[] = []
  let left = 0
  for (const share of parts) {
    const blockWidth = share * usable
    layout.push({ left, width: blockWidth })
    if (blockWidth > 0) left += blockWidth + gap
  }
  return layout
}
