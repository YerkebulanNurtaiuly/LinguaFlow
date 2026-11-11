export const cardCount = (n: number) => (n === 1 ? '1 card' : `${n} cards`)

export const dayCount = (n: number) => (n === 1 ? '1 day' : `${n} days`)

/** A calendar date ("2026-11-12") as a short weekday, in UTC so the day doesn't shift. */
export function formatWeekday(date: string): string {
  return new Intl.DateTimeFormat('en', {
    weekday: 'short',
    timeZone: 'UTC',
  }).format(new Date(`${date}T00:00:00Z`))
}

/** A time later today, like "15:42", in the viewer's time zone. */
export function formatTime(instant: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(instant))
}

/** A session's length, like "4 min 20 s" or "45 s". */
export function formatDuration(ms: number): string {
  const seconds = Math.max(0, Math.round(ms / 1000))
  const minutes = Math.floor(seconds / 60)
  return minutes > 0 ? `${minutes} min ${seconds % 60} s` : `${seconds} s`
}
