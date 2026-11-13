import { useState } from 'react'
import { ChartTooltip, Swatch } from './chartParts'
import { percent, segmentLayout, shares } from './chartMath'
import { STATUS_STEPS, type Status } from './statusSteps'
import { useElementWidth } from './useElementWidth'

/** Roughly what a share label needs: "100%" at 12px plus padding. */
const LABEL_ROOM = 40

/**
 * One whole split by knowledge status (FE-PRG-02): a tall segmented bar (28px), each
 * status its own block on the ordinal ramp from New to Mastered, its share printed
 * inside where it fits. Every block names its status, count and share on hover and
 * keyboard focus; the legend above names the blocks in order, and the page's table
 * view repeats the numbers.
 */
export function MasteryBar({
  name,
  counts,
}: {
  name: string
  counts: Record<Status, number>
}) {
  const [ref, width] = useElementWidth<HTMLDivElement>()
  const [active, setActive] = useState<number>()
  const values = STATUS_STEPS.map((step) => counts[step.status])
  const total = values.reduce((sum, count) => sum + count, 0)
  const parts = shares(values)
  const layout = segmentLayout(parts, width)
  const blocks = STATUS_STEPS.map((step, index) => ({
    ...step,
    index,
    left: layout[index]!.left,
    width: layout[index]!.width,
    count: values[index]!,
  }))

  return (
    <div>
      <p className="mb-1.5 text-sm font-medium text-fg">
        {name} <span className="font-normal text-fg-muted">· {total}</span>
      </p>
      <div ref={ref} className="relative">
        {total === 0 ? (
          <div className="h-7 rounded bg-surface-muted" />
        ) : (
          <div
            role="list"
            aria-label={`${name} by status`}
            className="relative h-7"
          >
            {blocks
              .filter((block) => block.width > 0)
              .map((block) => {
                const description = `${block.label}: ${block.count}, ${percent(parts[block.index]!)}`
                return (
                  <div
                    key={block.status}
                    role="listitem"
                    tabIndex={0}
                    aria-label={description}
                    className={`absolute top-0 flex h-7 items-center rounded px-1.5 text-xs font-semibold focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary ${block.fill} ${block.ink}`}
                    style={{ left: block.left, width: block.width }}
                    onPointerEnter={() => setActive(block.index)}
                    onPointerLeave={() => setActive(undefined)}
                    onFocus={() => setActive(block.index)}
                    onBlur={() => setActive(undefined)}
                  >
                    {block.width >= LABEL_ROOM && percent(parts[block.index]!)}
                  </div>
                )
              })}
          </div>
        )}
        {active !== undefined && (
          <ChartTooltip
            x={blocks[active]!.left + blocks[active]!.width / 2}
            y={34}
            width={width}
          >
            <p className="font-semibold text-fg">
              {blocks[active]!.count} · {percent(parts[active]!)}
            </p>
            <p className="text-fg-muted">{blocks[active]!.label}</p>
          </ChartTooltip>
        )}
      </div>
    </div>
  )
}

/** The status legend, names only, in block order (shared by every bar). */
export function StatusLegend() {
  return (
    <ul className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-fg-muted">
      {STATUS_STEPS.map((step) => (
        <li key={step.status} className="flex items-center gap-1.5">
          <Swatch className={step.fill} />
          {step.label}
        </li>
      ))}
    </ul>
  )
}
