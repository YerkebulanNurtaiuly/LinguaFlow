/**
 * Lesson dates are calendar dates ("2026-11-07"), not instants: format them as UTC so
 * the day never shifts with the viewer's time zone.
 */
const dayFormat = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeZone: 'UTC',
})
const monthFormat = new Intl.DateTimeFormat(undefined, {
  month: 'long',
  year: 'numeric',
  timeZone: 'UTC',
})

export const formatDay = (isoDate: string) =>
  dayFormat.format(new Date(`${isoDate}T00:00:00Z`))

/** "2026-11" → "November 2026" */
export const formatMonth = (yearMonth: string) =>
  monthFormat.format(new Date(`${yearMonth}-01T00:00:00Z`))

/** Today in the viewer's time zone, as yyyy-MM-dd for a date input. */
export function today(): string {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

const plural = (count: number, one: string, many: string) =>
  `${count.toLocaleString()} ${count === 1 ? one : many}`

export const wordCount = (count: number) => plural(count, 'word', 'words')
export const topicCount = (count: number) => plural(count, 'topic', 'topics')
export const lessonCount = (count: number) => plural(count, 'lesson', 'lessons')
