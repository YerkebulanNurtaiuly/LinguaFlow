import { useQuery } from '@tanstack/react-query'
import { fetchProgressStats, fetchWeakPoints } from './progressApi'

/** Progress figures; invalidate after reviews, exercises or practice sessions. */
export const PROGRESS_QUERY_KEY = ['progress'] as const

export function useProgressStats() {
  return useQuery({
    queryKey: [...PROGRESS_QUERY_KEY, 'stats'],
    queryFn: () => fetchProgressStats(),
  })
}

export function useWeakPoints() {
  return useQuery({
    queryKey: [...PROGRESS_QUERY_KEY, 'weak-points'],
    queryFn: () => fetchWeakPoints(),
  })
}
