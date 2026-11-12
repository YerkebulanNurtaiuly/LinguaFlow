import type { PracticeMode } from '@/practice/practiceApi'

export const MODE_LABELS: Record<PracticeMode, string> = {
  CURRENT_LESSON: 'Current lesson',
  MIXED: 'Mixed practice',
  FULL_REVISION: 'Full revision',
  WEAK_SPOTS: 'Weak spots',
}

/** "1 word", "3 words". */
export function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`
}

/** "45 s", "3 min". */
export function formatDuration(ms: number): string {
  const seconds = Math.round(ms / 1000)
  return seconds < 60 ? `${seconds} s` : `${Math.round(seconds / 60)} min`
}
