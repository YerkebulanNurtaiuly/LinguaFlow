import { useState } from 'react'
import type { ProgressStats } from '@/progress/progressApi'
import { activityLevel } from './chartMath'
import { ChartTooltip, Swatch } from './chartParts'
import { useElementWidth } from './useElementWidth'

type Day = ProgressStats['activity'][number]

/** Fill per activity level: 0 is the muted surface, 1–4 the ordinal blue ramp. */
const LEVEL_FILL = [
  'fill-surface-muted',
  'fill-viz-1',
  'fill-viz-2',
  'fill-viz-3',
  'fill-viz-4',
]
const LEVEL_SWATCH = ['bg-viz-1', 'bg-viz-2', 'bg-viz-3', 'bg-viz-4']

const WEEKDAYS = ['Mon', '', 'Wed', '', 'Fri', '', '']
const GAP = 2
const LABEL_WIDTH = 32
const MONTH_HEIGHT = 18

const utc = (iso: string) => new Date(`${iso}T00:00:00Z`)

/**
 * Practice per day as a calendar grid (FE-PRG-02): one column per week (Monday on
 * top), one hue, more practice darker; days without practice in the muted surface.
 * Each cell has a tooltip; the busiest day and the totals are in text below, and the
 * page's table lists every day.
 */
export function ActivityHeatmap({ days }: { days: Day[] }) {
  const [ref, width] = useElementWidth<HTMLDivElement>()
  const [hovered, setHovered] = useState<number>()
  if (days.length === 0) return null

  const first = utc(days[0]!.date)
  // Monday = 0
  const offset = (first.getUTCDay() + 6) % 7
  const columns = Math.ceil((offset + days.length) / 7)
  const cell = Math.max(
    6,
    Math.min(16, Math.floor((width - LABEL_WIDTH) / columns) - GAP),
  )
  const step = cell + GAP
  const max = Math.max(...days.map((day) => day.reviews + day.exercises))
  const svgWidth = LABEL_WIDTH + columns * step
  const svgHeight = MONTH_HEIGHT + 7 * step

  const position = (index: number) => {
    const slot = offset + index
    return {
      x: LABEL_WIDTH + Math.floor(slot / 7) * step,
      y: MONTH_HEIGHT + (slot % 7) * step,
    }
  }

  // A month label above the first column that starts in it
  const months: { x: number; label: string }[] = []
  days.forEach((day, index) => {
    const date = utc(day.date)
    if (date.getUTCDate() === 1 || index === 0) {
      const { x } = position(index)
      if (months.every((m) => x - m.x > 28)) {
        months.push({
          x,
          label: date.toLocaleDateString(undefined, {
            month: 'short',
            timeZone: 'UTC',
          }),
        })
      }
    }
  })

  const shown = hovered === undefined ? undefined : days[hovered]
  const shownAt = hovered === undefined ? undefined : position(hovered)

  return (
    <div ref={ref} className="relative">
      <svg
        width={svgWidth}
        height={svgHeight}
        role="img"
        aria-label={`Practice per day, ${days[0]!.date} to ${days.at(-1)!.date}`}
        className="max-w-full"
        onPointerLeave={() => setHovered(undefined)}
      >
        {months.map((month) => (
          <text
            key={month.x}
            x={month.x}
            y={12}
            className="fill-fg-muted text-[11px]"
          >
            {month.label}
          </text>
        ))}
        {WEEKDAYS.map((label, row) =>
          label ? (
            <text
              key={label}
              x={0}
              y={MONTH_HEIGHT + row * step + cell - 2}
              className="fill-fg-muted text-[11px]"
            >
              {label}
            </text>
          ) : null,
        )}
        {days.map((day, index) => {
          const { x, y } = position(index)
          const level = activityLevel(day.reviews + day.exercises, max)
          return (
            <rect
              key={day.date}
              x={x}
              y={y}
              width={cell}
              height={cell}
              rx={2}
              className={`${LEVEL_FILL[level]} ${hovered === index ? 'stroke-fg' : ''}`}
              strokeWidth={hovered === index ? 1.5 : 0}
              onPointerEnter={() => setHovered(index)}
            />
          )
        })}
      </svg>
      {shown && shownAt && (
        <ChartTooltip
          x={shownAt.x + cell / 2}
          y={shownAt.y + cell + 6}
          width={width}
        >
          <p className="font-semibold text-fg">
            {shown.reviews + shown.exercises} answers
          </p>
          <p className="text-fg-muted">
            {utc(shown.date).toLocaleDateString(undefined, {
              weekday: 'short',
              day: 'numeric',
              month: 'short',
              timeZone: 'UTC',
            })}
          </p>
          <p className="text-fg-muted">
            {shown.reviews} reviews · {shown.exercises} exercises ·{' '}
            {Math.round(shown.studiedMs / 60_000)} min
          </p>
        </ChartTooltip>
      )}
      <div className="mt-2 flex items-center gap-1.5 text-xs text-fg-muted">
        <span>Less</span>
        <Swatch className="bg-surface-muted" />
        {LEVEL_SWATCH.map((swatch) => (
          <Swatch key={swatch} className={swatch} />
        ))}
        <span>More</span>
      </div>
    </div>
  )
}
