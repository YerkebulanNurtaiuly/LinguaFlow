import { useQuery } from '@tanstack/react-query'
import { GENERATING_POLL_MS } from '@/exercises/queries'
import { fetchPracticeOverview, fetchPracticeSession } from './practiceApi'

export const PRACTICE_QUERY_KEY = ['practice'] as const

/** What each practice mode would use, and the session in progress. */
export function usePracticeOverview() {
  return useQuery({
    queryKey: [...PRACTICE_QUERY_KEY, 'overview'],
    queryFn: fetchPracticeOverview,
  })
}

/** One practice session; polled while its exercises are still being written. */
export function usePracticeSession(id: string) {
  return useQuery({
    queryKey: [...PRACTICE_QUERY_KEY, 'session', id],
    queryFn: () => fetchPracticeSession(id),
    refetchInterval: (query) =>
      query.state.data?.status === 'IN_PROGRESS' &&
      query.state.data.exerciseSet?.status === 'GENERATING'
        ? GENERATING_POLL_MS
        : false,
  })
}
