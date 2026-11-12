import { useQuery } from '@tanstack/react-query'
import { fetchExerciseSet } from './exercisesApi'

export const EXERCISES_QUERY_KEY = ['exercises'] as const

/** How often a set still being written is checked again. */
export const GENERATING_POLL_MS = 3000

/** One exercise set; polled while the AI is still writing it. */
export function useExerciseSet(id: string) {
  return useQuery({
    queryKey: [...EXERCISES_QUERY_KEY, 'set', id],
    queryFn: () => fetchExerciseSet(id),
    refetchInterval: (query) =>
      query.state.data?.status === 'GENERATING' ? GENERATING_POLL_MS : false,
  })
}
