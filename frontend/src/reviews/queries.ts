import { useQuery } from '@tanstack/react-query'
import { fetchReviewStats } from './reviewsApi'

/** Everything about flashcard reviews: statistics. Invalidate after grading cards. */
export const REVIEWS_QUERY_KEY = ['reviews'] as const

export function useReviewStats() {
  return useQuery({
    queryKey: [...REVIEWS_QUERY_KEY, 'stats'],
    queryFn: fetchReviewStats,
  })
}
