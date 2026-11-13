export type Status = 'NEW' | 'LEARNING' | 'LEARNED' | 'MASTERED'

/** Statuses in block order, each with its step of the ordinal ramp and label ink. */
export const STATUS_STEPS: {
  status: Status
  label: string
  fill: string
  ink: string
}[] = [
  { status: 'NEW', label: 'New', fill: 'bg-viz-1', ink: 'text-viz-1-ink' },
  {
    status: 'LEARNING',
    label: 'Learning',
    fill: 'bg-viz-2',
    ink: 'text-viz-2-ink',
  },
  {
    status: 'LEARNED',
    label: 'Learned',
    fill: 'bg-viz-3',
    ink: 'text-viz-3-ink',
  },
  {
    status: 'MASTERED',
    label: 'Mastered',
    fill: 'bg-viz-4',
    ink: 'text-viz-4-ink',
  },
]
