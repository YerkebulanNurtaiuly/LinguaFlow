import {
  useId,
  useRef,
  useState,
  type KeyboardEvent,
  type ReactNode,
} from 'react'
import { cn } from '@/lib/cn'

export interface TabItem {
  id: string
  label: ReactNode
  content: ReactNode
}

export interface TabsProps {
  items: TabItem[]
  /** Accessible name of the tab list */
  label: string
  defaultTab?: string
  /** Controlled mode */
  value?: string
  onChange?: (id: string) => void
}

/**
 * WAI-ARIA tabs: only the selected tab is in the Tab order; Arrow Left/Right move and
 * select (wrapping), Home/End jump to the ends.
 */
export function Tabs({ items, label, defaultTab, value, onChange }: TabsProps) {
  const [internal, setInternal] = useState(defaultTab ?? items[0]?.id)
  const selected = value ?? internal
  const baseId = useId()
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([])

  const select = (index: number) => {
    const item = items[index]
    if (!item) return
    setInternal(item.id)
    onChange?.(item.id)
    tabRefs.current[index]?.focus()
  }

  const onKeyDown = (event: KeyboardEvent, index: number) => {
    const last = items.length - 1
    const target = {
      ArrowRight: index === last ? 0 : index + 1,
      ArrowLeft: index === 0 ? last : index - 1,
      Home: 0,
      End: last,
    }[event.key]
    if (target === undefined) return
    event.preventDefault()
    select(target)
  }

  return (
    <div>
      <div
        role="tablist"
        aria-label={label}
        className="flex gap-1 border-b border-border"
      >
        {items.map((item, index) => {
          const isSelected = item.id === selected
          return (
            <button
              key={item.id}
              ref={(el) => {
                tabRefs.current[index] = el
              }}
              type="button"
              role="tab"
              id={`${baseId}-tab-${item.id}`}
              aria-selected={isSelected}
              aria-controls={`${baseId}-panel-${item.id}`}
              tabIndex={isSelected ? 0 : -1}
              onClick={() => select(index)}
              onKeyDown={(event) => onKeyDown(event, index)}
              className={cn(
                '-mb-px border-b-2 px-3 py-2 text-sm font-medium transition-colors',
                isSelected
                  ? 'border-primary text-primary-soft-fg'
                  : 'border-transparent text-fg-muted hover:text-fg',
              )}
            >
              {item.label}
            </button>
          )
        })}
      </div>
      {items.map((item) => (
        <div
          key={item.id}
          role="tabpanel"
          id={`${baseId}-panel-${item.id}`}
          aria-labelledby={`${baseId}-tab-${item.id}`}
          hidden={item.id !== selected}
          tabIndex={0}
          className="pt-4"
        >
          {item.id === selected && item.content}
        </div>
      ))}
    </div>
  )
}
