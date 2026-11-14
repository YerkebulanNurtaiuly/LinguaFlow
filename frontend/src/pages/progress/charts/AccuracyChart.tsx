import { useState, type KeyboardEvent } from 'react'
import type { ProgressStats } from '@/progress/progressApi'
import { ChartTooltip, LineKey } from './chartParts'
import { linePaths, percent, shortDate } from './chartMath'
import { useElementWidth } from './useElementWidth'

type Week = ProgressStats['accuracy'][number]

const SERIES = [
  {
    key: 'reviewRate' as const,
    label: 'Flashcards remembered',
    short: 'Cards',
    stroke: 'stroke-viz-series-1',
    fill: 'fill-viz-series-1',
  },
  {
    key: 'exerciseScore' as const,
    label: 'Exercise score',
    short: 'Exercises',
    stroke: 'stroke-viz-series-2',
    fill: 'fill-viz-series-2',
  },
]

const HEIGHT = 220
const MARGIN = { top: 12, right: 92, bottom: 28, left: 40 }
const TICKS = [0, 0.25, 0.5, 0.75, 1]

/**
 * Accuracy per week (FE-PRG-02): flashcards remembered and the average exercise
 * score, two lines on one 0–100% axis. A legend above, a label at each line's end, and
 * a crosshair (pointer or arrow keys) with both weeks' values; weeks without answers
 * leave a gap.
 */
export function AccuracyChart({ weeks }: { weeks: Week[] }) {
  const [ref, width] = useElementWidth<HTMLDivElement>()
  const [active, setActive] = useState<number>()
  const plotWidth = Math.max(120, width - MARGIN.left - MARGIN.right)
  const plotHeight = HEIGHT - MARGIN.top - MARGIN.bottom
  const x = (index: number) =>
    MARGIN.left +
    (weeks.length <= 1
      ? plotWidth / 2
      : (index / (weeks.length - 1)) * plotWidth)
  const y = (value: number) => MARGIN.top + (1 - value) * plotHeight

  const nearest = (clientX: number, left: number) => {
    const relative = clientX - left - MARGIN.left
    const index = Math.round((relative / plotWidth) * (weeks.length - 1))
    return Math.min(weeks.length - 1, Math.max(0, index))
  }

  const onKey = (event: KeyboardEvent) => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
    event.preventDefault()
    const from = active ?? weeks.length - 1
    setActive(
      Math.min(
        weeks.length - 1,
        Math.max(0, from + (event.key === 'ArrowRight' ? 1 : -1)),
      ),
    )
  }

  // Every 3rd week labelled, and always the last
  const labelEvery = Math.max(
    1,
    Math.ceil(weeks.length / Math.max(1, plotWidth / 70)),
  )
  const shown = active === undefined ? undefined : weeks[active]

  return (
    <div ref={ref} className="relative">
      <ul className="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-sm text-fg-muted">
        {SERIES.map((series) => (
          <li key={series.key} className="flex items-center gap-1.5">
            <LineKey className={series.stroke} />
            {series.label}
          </li>
        ))}
      </ul>
      <svg
        width={width}
        height={HEIGHT}
        role="img"
        tabIndex={0}
        aria-label="Accuracy per week; use the arrow keys to read each week"
        className="block max-w-full focus-visible:outline-2 focus-visible:outline-primary"
        onPointerMove={(event) =>
          setActive(
            nearest(
              event.clientX,
              event.currentTarget.getBoundingClientRect().left,
            ),
          )
        }
        onPointerLeave={() => setActive(undefined)}
        onKeyDown={onKey}
        onBlur={() => setActive(undefined)}
      >
        {TICKS.map((tick) => (
          <g key={tick}>
            <line
              x1={MARGIN.left}
              x2={MARGIN.left + plotWidth}
              y1={y(tick)}
              y2={y(tick)}
              strokeWidth={1}
              className={tick === 0 ? 'stroke-border-strong' : 'stroke-border'}
            />
            <text
              x={MARGIN.left - 6}
              y={y(tick) + 4}
              textAnchor="end"
              className="fill-fg-muted text-[11px] tabular-nums"
            >
              {percent(tick)}
            </text>
          </g>
        ))}
        {weeks.map((week, index) =>
          index % labelEvery === 0 || index === weeks.length - 1 ? (
            <text
              key={week.weekStart}
              x={x(index)}
              y={HEIGHT - 8}
              textAnchor="middle"
              className="fill-fg-muted text-[11px]"
            >
              {shortDate(week.weekStart)}
            </text>
          ) : null,
        )}
        {active !== undefined && (
          <line
            x1={x(active)}
            x2={x(active)}
            y1={MARGIN.top}
            y2={MARGIN.top + plotHeight}
            strokeWidth={1}
            className="stroke-border-strong"
          />
        )}
        {SERIES.map((series) => {
          const values = weeks.map((week) => week[series.key])
          const lastIndex = values.findLastIndex((value) => value !== undefined)
          const last = lastIndex >= 0 ? values[lastIndex] : undefined
          return (
            <g key={series.key}>
              {linePaths(values, x, y).map((d) => (
                <path
                  key={d}
                  d={d}
                  fill="none"
                  strokeWidth={2}
                  strokeLinejoin="round"
                  strokeLinecap="round"
                  className={series.stroke}
                />
              ))}
              {/* Weeks standing alone get a dot, so a single week still shows */}
              {values.map((value, index) =>
                value !== undefined &&
                values[index - 1] === undefined &&
                values[index + 1] === undefined ? (
                  <circle
                    key={index}
                    cx={x(index)}
                    cy={y(value)}
                    r={4}
                    className={`${series.fill} stroke-surface`}
                    strokeWidth={2}
                  />
                ) : null,
              )}
              {active !== undefined && values[active] !== undefined && (
                <circle
                  cx={x(active)}
                  cy={y(values[active])}
                  r={5}
                  className={`${series.fill} stroke-surface`}
                  strokeWidth={2}
                />
              )}
              {last !== undefined && (
                <text
                  x={x(lastIndex) + 8}
                  y={y(last) + 4 + (series.key === 'exerciseScore' ? 12 : 0)}
                  className="fill-fg-muted text-[11px]"
                >
                  {series.short} {percent(last)}
                </text>
              )}
            </g>
          )
        })}
      </svg>
      {shown && active !== undefined && (
        <ChartTooltip x={x(active)} y={MARGIN.top + 8} width={width}>
          <p className="text-fg-muted">Week of {shortDate(shown.weekStart)}</p>
          {SERIES.map((series) => (
            <p key={series.key} className="flex items-center gap-2">
              <LineKey className={series.stroke} />
              <span className="font-semibold text-fg">
                {shown[series.key] === undefined
                  ? '–'
                  : percent(shown[series.key]!)}
              </span>
              <span className="text-fg-muted">{series.short}</span>
            </p>
          ))}
          <p className="text-fg-subtle">
            {shown.reviews} reviews · {shown.exercises} exercises
          </p>
        </ChartTooltip>
      )}
    </div>
  )
}
