/** 95 000 ms → "2 min"; an hour or more → "1 h 35 min". */
export function formatMinutes(ms: number): string {
  const minutes = Math.round(ms / 60_000)
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`
}

/** WRONG_CASE → "Wrong case". */
export function humanizeCode(code: string): string {
  const words = code.toLowerCase().replaceAll('_', ' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}
