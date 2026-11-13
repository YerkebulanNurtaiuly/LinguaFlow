import { keepPreviousData, useQuery } from '@tanstack/react-query'
import {
  fetchFrequentMistakes,
  fetchProgressStats,
  fetchWeakPoints,
  type MistakeGrouping,
} from './progressApi'

/** Progress figures; invalidate after reviews, exercises or practice sessions. */
export const PROGRESS_QUERY_KEY = ['progress'] as const

/** Statistics with a {@code days}-long activity range (default: the dashboard's week). */
export function useProgressStats(days = 7) {
  const weeks = Math.min(52, Math.max(4, Math.ceil(days / 7)))
  return useQuery({
    queryKey: [...PROGRESS_QUERY_KEY, 'stats', days],
    queryFn: () => fetchProgressStats(days, weeks),
    // A new period keeps the old charts on screen while it loads
    placeholderData: keepPreviousData,
  })
}

export function useWeakPoints(limit = 5) {
  return useQuery({
    queryKey: [...PROGRESS_QUERY_KEY, 'weak-points', limit],
    queryFn: () => fetchWeakPoints(limit),
  })
}

export function useFrequentMistakes(groupBy: MistakeGrouping, days: number) {
  return useQuery({
    queryKey: [...PROGRESS_QUERY_KEY, 'mistakes', groupBy, days],
    queryFn: () => fetchFrequentMistakes(groupBy, days),
    placeholderData: keepPreviousData,
  })
}
