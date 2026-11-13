import { plural } from '@/pages/practice/practiceText'
import type { WeakPointReason } from '@/progress/progressApi'

/** A weak point's reason in words. */
export function reasonText(reason: WeakPointReason): string {
  switch (reason.code) {
    case 'LOW_ACCURACY':
      return `${Math.round(reason.value * 100)}% right lately`
    case 'FREQUENT_MISTAKE':
      return `${humanize(reason.errorType ?? 'mistake')} ${reason.value}×`
    case 'LAPSES':
      return `forgotten ${plural(reason.value, 'time')}`
    case 'NOT_PRACTISED_RECENTLY':
      return `not practised for ${plural(reason.value, 'day')}`
  }
}

/** WRONG_CASE → "Wrong case". */
function humanize(code: string): string {
  const words = code.toLowerCase().replaceAll('_', ' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}
